-- =============================================================================================
-- V18: 6B 중간 회신(2026-10-09) ①②④⑤.
--   ① contract_link_batch — 계약 연결 배치 원장. (tenant, source, batch_id)마다 내용 해시(JCS) 하나: 같은 참조·같은 해시는 재생, 다른 해시는
--      배치 전체 거부(앱 BATCH_REF_REUSED). 수신 때 행을 넣고(요약 없음) 끝나면 요약을 한 번 쓴다. 지우지 않는다(GD138).
--   ② identity_link.feed_sources — 계약 피드 주체가 보낼 수 있는 출처(닫힌 목록, CONTRACT_FEED이면 필수·비어 있지 않음, 아니면 없음).
--   ④ 법적 보류와 폐기·파기의 경합: 보류 INSERT가 대상 행(확인서 또는 customer_ref)을 FOR UPDATE로 잠근 뒤, 이미 지워진 대상(파기·폐기·문서 키
--      파기)이면 거부한다(GD139 — 묘비에 보류는 의미가 없다). 파기 전제·폐기 함수는 고객 보류 검사 전에 customer_ref를 FOR SHARE로 잠근다.
--      잠금 순서(설계서 §9): 확인서 → customer_ref → 세션.
--   ⑤ audit_log(tenant_id, target_id, seq DESC) — 방치 초안 배치의 "마지막 변경" 조회.
-- 기존 V* 파일은 고치지 않는다 — 함수는 CREATE OR REPLACE.
-- =============================================================================================

-- ① 배치 원장 -----------------------------------------------------------------------------------
CREATE TABLE contract_link_batch (
    tenant_id      TEXT        NOT NULL REFERENCES tenant,
    source         TEXT        NOT NULL,
    batch_id       TEXT        NOT NULL,
    content_sha256 TEXT        NOT NULL,
    items          INTEGER     NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL,
    received_by    TEXT        NOT NULL,
    summary        JSONB,
    completed_at   TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, source, batch_id),
    CONSTRAINT ck_contract_link_batch_values CHECK (source ~ '^[A-Z][A-Z0-9_]{0,31}$' AND batch_id ~ '^[A-Za-z0-9._:-]{1,64}$'
        AND content_sha256 ~ '^[0-9a-f]{64}$' AND items BETWEEN 1 AND 5000 AND btrim(received_by) <> ''),
    CONSTRAINT ck_contract_link_batch_summary CHECK ((summary IS NULL) = (completed_at IS NULL)
        AND (summary IS NULL OR jsonb_typeof(summary) = 'object'))
);

CREATE FUNCTION ga_contract_link_batch_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'contract_link_batch is a ledger: % rejected', TG_OP USING ERRCODE = 'GD138';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.summary IS NOT NULL THEN
            RAISE EXCEPTION 'a contract link batch is recorded on receipt, without a summary' USING ERRCODE = 'GD138';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.summary IS NOT NULL OR NEW.summary IS NULL
        OR (to_jsonb(NEW) - ARRAY['summary', 'completed_at']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['summary', 'completed_at']) THEN
        RAISE EXCEPTION 'contract link batch %/% gets its summary once and nothing else changes', OLD.source, OLD.batch_id USING ERRCODE = 'GD138';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_contract_link_batch_guard
    BEFORE INSERT OR UPDATE OR DELETE ON contract_link_batch
    FOR EACH ROW EXECUTE FUNCTION ga_contract_link_batch_guard();
CREATE TRIGGER trg_contract_link_batch_no_truncate
    BEFORE TRUNCATE ON contract_link_batch
    FOR EACH STATEMENT EXECUTE FUNCTION ga_contract_link_batch_guard();

ALTER TABLE contract_link_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE contract_link_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contract_link_batch
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
REVOKE ALL ON TABLE contract_link_batch FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE contract_link_batch TO disclosure_app;
GRANT UPDATE (summary, completed_at) ON TABLE contract_link_batch TO disclosure_app;
REVOKE ALL ON FUNCTION ga_contract_link_batch_guard() FROM PUBLIC;

-- ② 계약 피드 주체의 출처 ------------------------------------------------------------------------
CREATE FUNCTION ga_feed_sources_valid(s TEXT[]) RETURNS BOOLEAN
    LANGUAGE sql
    IMMUTABLE
AS $$
    SELECT cardinality(s) >= 1
       AND NOT EXISTS (SELECT 1 FROM unnest(s) AS x(v) WHERE v IS NULL OR v !~ '^[A-Z][A-Z0-9_]{0,31}$')
       AND cardinality(s) = (SELECT count(DISTINCT v) FROM unnest(s) AS x(v))
$$;
REVOKE ALL ON FUNCTION ga_feed_sources_valid(TEXT[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ga_feed_sources_valid(TEXT[]) TO disclosure_app;

ALTER TABLE identity_link ADD COLUMN feed_sources TEXT[];
ALTER TABLE identity_link ADD CONSTRAINT ck_identity_link_feed_sources CHECK (
    ('CONTRACT_FEED' = ANY (roles)) = (feed_sources IS NOT NULL)
    AND (feed_sources IS NULL OR ga_feed_sources_valid(feed_sources)));

-- ④ 보류 설정: 대상 행을 잠그고, 지워진 대상은 거부 -------------------------------------------------
CREATE OR REPLACE FUNCTION ga_legal_hold_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    st   TEXT;
    gone TIMESTAMPTZ;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'legal_hold rows are never removed (%); release the hold instead', TG_OP USING ERRCODE = 'GD112';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.released_at IS NOT NULL THEN
            RAISE EXCEPTION 'legal hold % starts PLACED', NEW.hold_id USING ERRCODE = 'GD112';
        END IF;
        -- V18: 대상 행을 잠근다 — 폐기·파기(확인서 FOR UPDATE, 고객 FOR SHARE)와 직렬화. 지워진 대상(파기·폐기·문서 키 파기)에는 보류를 걸지 않는다
        IF NEW.disclosure_id IS NOT NULL THEN
            SELECT d.status, d.destroyed_at INTO st, gone
              FROM disclosure d
             WHERE d.tenant_id = NEW.tenant_id AND d.disclosure_id = NEW.disclosure_id
               FOR UPDATE;
            IF gone IS NOT NULL OR st = 'ABANDONED'
                OR EXISTS (SELECT 1 FROM document_key k
                            WHERE k.tenant_id = NEW.tenant_id AND k.disclosure_id = NEW.disclosure_id AND k.shredded_at IS NOT NULL) THEN
                RAISE EXCEPTION 'disclosure % is already destroyed or abandoned — nothing left to hold', NEW.disclosure_id USING ERRCODE = 'GD139';
            END IF;
        ELSE
            SELECT c.destroyed_at INTO gone
              FROM customer_ref c
             WHERE c.tenant_id = NEW.tenant_id AND c.customer_ref = NEW.customer_ref
               FOR UPDATE;
            IF gone IS NOT NULL THEN
                RAISE EXCEPTION 'customer_ref is already destroyed — nothing left to hold' USING ERRCODE = 'GD139';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF ga_destroy_branch(CASE WHEN OLD.disclosure_id IS NOT NULL THEN 'disclosure:' || OLD.disclosure_id
                              ELSE 'customer_ref:' || OLD.customer_ref END) THEN
        IF OLD.released_at IS NULL OR NOT ga_nulls_only(to_jsonb(OLD), to_jsonb(NEW), ARRAY['reason_text']) THEN
            RAISE EXCEPTION 'legal hold % destruction nulls only reason_text of a released hold', OLD.hold_id USING ERRCODE = 'GD113';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.released_at IS NOT NULL OR NEW.released_at IS NULL
        OR (to_jsonb(NEW) - ARRAY['released_by', 'released_at', 'release_reason_code'])
           IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['released_by', 'released_at', 'release_reason_code']) THEN
        RAISE EXCEPTION 'legal hold % moves PLACED → RELEASED once and nothing else changes', OLD.hold_id USING ERRCODE = 'GD112';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION ga_legal_hold_guard() FROM PUBLIC;

-- 파기 전제(파기·문서 키 파기가 함께 쓴다): 고객 보류 검사 전에 고객 행 공유 잠금
CREATE OR REPLACE FUNCTION ga_destroy_preconditions(p_tenant TEXT, p_disclosure UUID, p_as_of DATE) RETURNS VOID
    LANGUAGE plpgsql
AS $$
DECLARE
    d RECORD;
BEGIN
    IF p_tenant IS DISTINCT FROM current_setting('app.tenant_id', true) THEN
        RAISE EXCEPTION 'destruction runs in the bound tenant only' USING ERRCODE = 'GD114';
    END IF;
    SELECT x.status, x.disclosure_no, x.retention_until, x.destroyed_at, x.customer_ref INTO d
      FROM disclosure x
     WHERE x.tenant_id = p_tenant AND x.disclosure_id = p_disclosure
       FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'disclosure % not found', p_disclosure USING ERRCODE = 'GD114';
    END IF;
    IF d.disclosure_no IS NULL OR d.status NOT IN ('COMPLETED', 'EXPIRED', 'VOID', 'SUPERSEDED') THEN
        RAISE EXCEPTION 'disclosure % is % and not a sealed terminal document', p_disclosure, d.status USING ERRCODE = 'GD114';
    END IF;
    IF d.destroyed_at IS NOT NULL THEN
        RAISE EXCEPTION 'disclosure % is already destroyed', p_disclosure USING ERRCODE = 'GD114';
    END IF;
    IF d.retention_until IS NULL OR d.retention_until >= p_as_of THEN
        RAISE EXCEPTION 'disclosure % is retained until % (judged on %)', p_disclosure, d.retention_until, p_as_of USING ERRCODE = 'GD114';
    END IF;
    -- V18: 고객 보류 검사 전에 고객 행을 공유 잠금 — 보류 설정(고객 행 FOR UPDATE)과 직렬화된다(잠금 순서 확인서 → customer_ref)
    PERFORM 1 FROM customer_ref c WHERE c.tenant_id = p_tenant AND c.customer_ref = d.customer_ref FOR SHARE;
    IF EXISTS (SELECT 1 FROM legal_hold h
                WHERE h.tenant_id = p_tenant AND h.released_at IS NULL
                  AND (h.disclosure_id = p_disclosure OR h.customer_ref = d.customer_ref)) THEN
        RAISE EXCEPTION 'disclosure % is under legal hold', p_disclosure USING ERRCODE = 'GD114';
    END IF;
END
$$;

-- 폐기 함수(V17 판 + 고객 행 공유 잠금) — 소유자(정의자 롤)로 교체한다
GRANT CREATE ON SCHEMA public TO disclosure_destroy_definer;
SET ROLE disclosure_destroy_definer;

CREATE OR REPLACE FUNCTION ga_draft_abandon(p_tenant TEXT, p_disclosure UUID, p_at TIMESTAMPTZ, p_by TEXT) RETURNS VOID
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    st TEXT;
    gone TIMESTAMPTZ;
    cust TEXT;
BEGIN
    IF p_tenant IS NULL OR p_disclosure IS NULL OR p_at IS NULL OR p_by IS NULL OR btrim(p_by) = '' THEN
        RAISE EXCEPTION 'tenant, disclosure, time and actor are required' USING ERRCODE = '22004';
    END IF;
    IF p_tenant IS DISTINCT FROM current_setting('app.tenant_id', true) THEN
        RAISE EXCEPTION 'abandonment runs in the bound tenant only' USING ERRCODE = 'GD133';
    END IF;
    SELECT d.status, d.destroyed_at, d.customer_ref INTO st, gone, cust
      FROM disclosure d
     WHERE d.tenant_id = p_tenant AND d.disclosure_id = p_disclosure
       FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'disclosure % not found', p_disclosure USING ERRCODE = 'GD133';
    END IF;
    IF NOT ga_is_mutable_status(st) OR gone IS NOT NULL THEN
        RAISE EXCEPTION 'disclosure % is % — only a draft before sealing is abandoned', p_disclosure, st USING ERRCODE = 'GD133';
    END IF;
    -- V17: 법적 보류(확인서 또는 그 고객)가 걸린 초안은 폐기하지 않는다 — 파기 전제(ga_destroy_preconditions)와 같은 조건
    -- V18: 고객 행을 공유 잠금한 뒤 본다(보류 설정과 직렬화 — 잠금 순서 확인서 → customer_ref)
    PERFORM 1 FROM customer_ref c WHERE c.tenant_id = p_tenant AND c.customer_ref = cust FOR SHARE;
    IF EXISTS (SELECT 1 FROM legal_hold h
                WHERE h.tenant_id = p_tenant AND h.released_at IS NULL
                  AND (h.disclosure_id = p_disclosure OR h.customer_ref = cust)) THEN
        RAISE EXCEPTION 'disclosure % is under legal hold', p_disclosure USING ERRCODE = 'GD137';
    END IF;
    PERFORM set_config('ga.destroy', 'abandon:' || p_disclosure, true);
    UPDATE recommendation SET reason_text = NULL
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure AND reason_text IS NOT NULL;
    UPDATE review SET reason = NULL
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure AND reason IS NOT NULL;
    UPDATE disclosure_item SET field_values = '{}'::jsonb
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure AND field_values <> '{}'::jsonb;
    UPDATE disclosure
       SET status = 'ABANDONED', abandoned_at = p_at, application_no = NULL
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure;
    PERFORM set_config('ga.destroy', '', true);
END
$$;

RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM disclosure_destroy_definer;

-- ⑤ 감사 대상 조회 인덱스 -------------------------------------------------------------------------
CREATE INDEX ix_audit_log_target ON audit_log (tenant_id, target_id, seq DESC);
