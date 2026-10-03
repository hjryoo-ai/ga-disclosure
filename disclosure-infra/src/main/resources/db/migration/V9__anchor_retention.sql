-- =============================================================================================
-- V9__anchor_retention.sql — Phase 5: 일일 앵커·영수증, 법적 보류, 보존기간 종료 파기(묘비)
--
-- (5 계획 §1, 승인 2026-10-03 Q2·Q8)
--   anchor          : 테넌트·KST 날짜당 1행. 두 체인 머리와 잎 해시 — DB가 잎을 다시 계산한다(GD110).
--   anchor_receipt  : 앵커당 최대 1행. 루트·경로·TSA 토큰을 영수증마다 중복 저장(테넌트 없는 배치 테이블 없음).
--                     DB가 잎 → 경로 → 루트를 다시 계산한다(GD111).
--   legal_hold      : 확인서 또는 고객 하나에 대한 보류. PLACED → RELEASED 1회(GD112).
--   파기             : 지정 개인정보 컬럼만 NULL(묘비). 번호·상태·해시·시각·체인은 남는다(CLAUDE.md 규칙 2).
--                     함수 3개(ga_document_key_shred, ga_disclosure_destroy, ga_customer_ref_destroy)만이 하고,
--                     함수의 정의자는 테이블 소유자가 아닌 전용 롤 disclosure_destroy_definer, 실행 권한은 disclosure_destroyer만.
--                     앱은 트랜잭션 안에서 SET LOCAL ROLE disclosure_destroyer로만 함수를 부른다(감사·아웃박스는 같은 트랜잭션에
--                     일반 경로로 먼저 적재). 불변 트리거는 "정의자 롤 + 함수가 세운 표식(ga.destroy)"일 때만 파기 분기를 연다.
--   그 밖            : disclosure.void_reason 구 컬럼 제거(V8 이관의 둘째 단계), 미사용 audit_anchor 제거(행 0 단언).
--
-- 롤(disclosure_destroyer, disclosure_destroy_definer)과 멤버십은 클러스터 수준이라 docker/postgres/init-roles.sql이 만든다
-- (마이그레이터는 NOCREATEROLE). 여기서는 존재·멤버십을 단언한다.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 0. 롤 단언
-- ---------------------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'disclosure_destroyer' AND NOT rolcanlogin AND NOT rolbypassrls AND NOT rolsuper)
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'disclosure_destroy_definer' AND NOT rolcanlogin AND NOT rolbypassrls AND NOT rolsuper) THEN
        RAISE EXCEPTION 'V9 needs roles disclosure_destroyer and disclosure_destroy_definer (NOLOGIN, NOBYPASSRLS) — see docker/postgres/init-roles.sql';
    END IF;
    IF NOT pg_has_role('disclosure_app', 'disclosure_destroyer', 'SET')
        OR pg_has_role('disclosure_app', 'disclosure_destroyer', 'USAGE')
        OR pg_has_role('disclosure_app', 'disclosure_destroy_definer', 'SET')
        OR NOT pg_has_role('disclosure_migrator', 'disclosure_destroy_definer', 'SET') THEN
        RAISE EXCEPTION 'V9 needs disclosure_app → disclosure_destroyer (SET, NOINHERIT) only and disclosure_migrator → disclosure_destroy_definer (SET)';
    END IF;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 1. audit_anchor 제거(V1, 쓰는 코드 없음 — anchor가 대체). 행이 있으면 제거하지 않고 실패한다(승인 Q8).
--    소유 롤도 FORCE RLS 대상이라 세는 동안만 두 표의 FORCE를 푼다(V8 이관과 같은 방식, 같은 트랜잭션). 세기도 테넌트 단위
--    조인이다(규칙 5 스캔). tenant의 FORCE를 풀지 않으면 조인이 0으로 보여 데이터가 있어도 지나간다(V9MigrationIT가 잡는다).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE audit_anchor NO FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$
DECLARE
    n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM audit_anchor a JOIN tenant t ON t.tenant_id = a.tenant_id;
    IF n <> 0 THEN
        RAISE EXCEPTION 'audit_anchor holds % row(s); V9 does not drop data — keep the table and report (Phase 5 approval Q8)', n;
    END IF;
END
$$;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
DROP TABLE audit_anchor;

-- ---------------------------------------------------------------------------------------------
-- 2. disclosure: 구 무효 사유 컬럼 제거, 파기 컬럼
-- ---------------------------------------------------------------------------------------------
ALTER TABLE disclosure DROP COLUMN void_reason;   -- ck_disclosure_legacy_void_reason도 함께 사라진다
ALTER TABLE disclosure
    ADD COLUMN destroyed_at TIMESTAMPTZ,
    ADD COLUMN destroyed_by TEXT,
    ADD CONSTRAINT ck_disclosure_destroyed CHECK ((destroyed_at IS NULL) = (destroyed_by IS NULL)
        AND (destroyed_by IS NULL OR btrim(destroyed_by) <> ''));

ALTER TABLE customer_ref
    ADD COLUMN destroyed_at TIMESTAMPTZ,
    ADD COLUMN destroyed_by TEXT,
    ALTER COLUMN name_enc DROP NOT NULL,
    ADD CONSTRAINT ck_customer_ref_destroyed CHECK ((destroyed_at IS NULL) = (destroyed_by IS NULL)
        AND (destroyed_by IS NULL OR btrim(destroyed_by) <> '')
        AND (destroyed_at IS NULL) = (name_enc IS NOT NULL)
        AND (destroyed_at IS NULL OR (phone_enc IS NULL AND birth_date_enc IS NULL AND crm_customer_id IS NULL)));

-- review.reason: 파기가 NULL로 만들 수 있게 NOT NULL을 풀고, 삽입 때 필수는 삽입 트리거가 지킨다
ALTER TABLE review ALTER COLUMN reason DROP NOT NULL;
ALTER TABLE review DROP CONSTRAINT ck_review_reason;
ALTER TABLE review ADD CONSTRAINT ck_review_reason CHECK (reason IS NULL OR btrim(reason) <> '');

-- ---------------------------------------------------------------------------------------------
-- 3. 파기 분기 공통 함수
--    ga_destroy_branch(target): 정의자 롤이 함수 안에서 세운 표식이 이 행을 가리키는가. 앱 롤은 표식을 세울 수 있어도
--    current_user가 다르다. 파기자 롤(SET ROLE)은 테이블 권한이 없다. 정의자 롤로 표식 없이 UPDATE하면 false.
--    ga_nulls_only(old, new, cols): cols 밖은 그대로이고 cols는 전부 NULL이 되었는가.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION ga_destroy_branch(p_target TEXT) RETURNS BOOLEAN
    LANGUAGE sql STABLE
AS $$
    SELECT current_user = 'disclosure_destroy_definer' AND coalesce(current_setting('ga.destroy', true), '') = p_target
$$;

CREATE FUNCTION ga_nulls_only(p_old JSONB, p_new JSONB, p_cols TEXT[]) RETURNS BOOLEAN
    LANGUAGE sql IMMUTABLE
AS $$
    SELECT (p_new - p_cols) = (p_old - p_cols)
       AND NOT EXISTS (SELECT 1 FROM unnest(p_cols) c WHERE (p_new -> c) IS NOT NULL AND (p_new -> c) <> 'null'::jsonb)
$$;

-- ---------------------------------------------------------------------------------------------
-- 4. 불변 트리거에 파기 분기 — 각 분기는 맨 앞에서만 열리고, 그 밖은 기존 규칙 그대로다.
-- ---------------------------------------------------------------------------------------------
-- disclosure: 지정 컬럼(무효·정정 사유 텍스트, 증권번호)만 NULL, 같은 문장에서 destroyed_at·destroyed_by 설정.
-- 파기된 행은 그 뒤 어떤 변경도 거부(GD113). destroyed_* 는 파기 분기 밖에서 바뀌지 않는다(GD113).
CREATE OR REPLACE FUNCTION ga_disclosure_guard_update() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    meta CONSTANT TEXT[] := ARRAY['status', 'superseded_by_id', 'completed_at', 'voided_at',
                                  'void_reason_code', 'void_reason_text', 'supersede_reason_code', 'supersede_reason_text',
                                  'policy_no', 'contract_date', 'retention_until'];
    erased CONSTANT TEXT[] := ARRAY['void_reason_text', 'supersede_reason_text', 'policy_no'];
BEGIN
    IF ga_destroy_branch('disclosure:' || OLD.disclosure_id) THEN
        IF OLD.destroyed_at IS NOT NULL OR NEW.destroyed_at IS NULL OR NEW.destroyed_by IS NULL
            OR NOT ga_nulls_only(to_jsonb(OLD) - ARRAY['destroyed_at', 'destroyed_by'],
                                 to_jsonb(NEW) - ARRAY['destroyed_at', 'destroyed_by'], erased) THEN
            RAISE EXCEPTION 'disclosure % destruction nulls only the designated columns and sets destroyed_at once', OLD.disclosure_id
                USING ERRCODE = 'GD113';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.destroyed_at IS NOT NULL THEN
        RAISE EXCEPTION 'disclosure % was destroyed at % and no longer changes', OLD.disclosure_id, OLD.destroyed_at
            USING ERRCODE = 'GD113';
    END IF;
    IF NEW.destroyed_at IS DISTINCT FROM OLD.destroyed_at OR NEW.destroyed_by IS DISTINCT FROM OLD.destroyed_by THEN
        RAISE EXCEPTION 'disclosure % destroyed_at is set only by ga_disclosure_destroy', OLD.disclosure_id
            USING ERRCODE = 'GD113';
    END IF;
    IF NOT ga_is_mutable_status(OLD.status) THEN
        IF (to_jsonb(NEW) - meta) IS DISTINCT FROM (to_jsonb(OLD) - meta) THEN
            RAISE EXCEPTION 'disclosure % is % : body columns are immutable after sealing', OLD.disclosure_id, OLD.status
                USING ERRCODE = 'GD001', HINT = 'correct via SUPERSEDE (new version) or VOID';
        END IF;
        IF ga_is_mutable_status(NEW.status) THEN
            RAISE EXCEPTION 'disclosure % cannot move from % back to mutable status %', OLD.disclosure_id, OLD.status, NEW.status
                USING ERRCODE = 'GD003';
        END IF;
    END IF;
    IF OLD.superseded_by_id IS NOT NULL AND NEW.superseded_by_id IS DISTINCT FROM OLD.superseded_by_id THEN
        RAISE EXCEPTION 'disclosure % superseded_by_id is write-once', OLD.disclosure_id
            USING ERRCODE = 'GD004';
    END IF;
    IF (OLD.void_reason_code IS NOT NULL
            AND (NEW.void_reason_code, NEW.void_reason_text) IS DISTINCT FROM (OLD.void_reason_code, OLD.void_reason_text))
        OR (OLD.supersede_reason_code IS NOT NULL
            AND (NEW.supersede_reason_code, NEW.supersede_reason_text) IS DISTINCT FROM (OLD.supersede_reason_code, OLD.supersede_reason_text))
        OR (OLD.voided_at IS NOT NULL AND NEW.voided_at IS DISTINCT FROM OLD.voided_at)
        OR (OLD.completed_at IS NOT NULL AND NEW.completed_at IS DISTINCT FROM OLD.completed_at) THEN
        RAISE EXCEPTION 'disclosure % void/supersede reasons, voided_at and completed_at are written once', OLD.disclosure_id
            USING ERRCODE = 'GD100';
    END IF;
    RETURN NEW;
END
$$;

-- disclosure_item·recommendation: 추천사유 텍스트만 파기 분기(recommendation)
CREATE OR REPLACE FUNCTION ga_child_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'recommendation' AND ga_destroy_branch('disclosure:' || OLD.disclosure_id) THEN
        IF NOT ga_nulls_only(to_jsonb(OLD), to_jsonb(NEW), ARRAY['reason_text']) THEN
            RAISE EXCEPTION 'recommendation destruction nulls only reason_text' USING ERRCODE = 'GD113';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM ga_require_mutable_parent(OLD.tenant_id, OLD.disclosure_id, TG_TABLE_NAME);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM ga_require_mutable_parent(NEW.tenant_id, NEW.disclosure_id, TG_TABLE_NAME);
        RETURN NEW;
    END IF;
    RETURN OLD;
END
$$;

-- signature·review: append-only였던 UPDATE를 파기 분기 하나만 여는 함수로 바꾼다(DELETE·TRUNCATE는 그대로 GD030)
CREATE FUNCTION ga_signature_guard_update() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF ga_destroy_branch('disclosure:' || OLD.disclosure_id)
        AND ga_nulls_only(to_jsonb(OLD), to_jsonb(NEW), ARRAY['device', 'ip', 'view_evidence']) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'signature is append-only: UPDATE rejected' USING ERRCODE = 'GD030';
END
$$;
DROP TRIGGER trg_signature_append_only ON signature;
CREATE TRIGGER trg_signature_append_only
    BEFORE DELETE ON signature
    FOR EACH ROW EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_signature_guard_update
    BEFORE UPDATE ON signature
    FOR EACH ROW EXECUTE FUNCTION ga_signature_guard_update();

CREATE FUNCTION ga_review_guard_update() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF ga_destroy_branch('disclosure:' || OLD.disclosure_id)
        AND ga_nulls_only(to_jsonb(OLD), to_jsonb(NEW), ARRAY['reason']) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'review is append-only: UPDATE rejected' USING ERRCODE = 'GD030';
END
$$;
DROP TRIGGER trg_review_append_only ON review;
CREATE TRIGGER trg_review_append_only
    BEFORE DELETE ON review
    FOR EACH ROW EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_review_guard_update
    BEFORE UPDATE ON review
    FOR EACH ROW EXECUTE FUNCTION ga_review_guard_update();

-- review 삽입 때 사유는 필수(NOT NULL을 풀었으므로 삽입 트리거가 지킨다 — V6 삽입 가드 다음에 이름 순으로 돈다)
CREATE FUNCTION ga_review_reason_required() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reason IS NULL THEN
        RAISE EXCEPTION 'review reason is required' USING ERRCODE = '23502';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_review_reason_required
    BEFORE INSERT ON review
    FOR EACH ROW EXECUTE FUNCTION ga_review_reason_required();

-- sign_session: 열람 증거만 파기 분기(닫힌 세션이라 그 밖의 변경은 여전히 GD101)
CREATE OR REPLACE FUNCTION ga_sign_session_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_status TEXT;
    parent_doc    TEXT;
    parent_pdf    TEXT;
    fixed CONSTANT TEXT[] := ARRAY['tenant_id', 'session_id', 'disclosure_id', 'signer_role', 'channel', 'token_hash', 'issued_by',
                                   'issued_at', 'expires_at', 'signed_doc_hash', 'signed_pdf_hash'];
BEGIN
    IF TG_OP = 'UPDATE' AND ga_destroy_branch('disclosure:' || OLD.disclosure_id) THEN
        IF NOT ga_nulls_only(to_jsonb(OLD), to_jsonb(NEW), ARRAY['view_evidence']) THEN
            RAISE EXCEPTION 'sign_session destruction nulls only view_evidence' USING ERRCODE = 'GD113';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'sign_session rows are never removed (%); revoke the session instead', TG_OP
            USING ERRCODE = 'GD101';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT d.status, d.canonical_hash, d.pdf_hash INTO parent_status, parent_doc, parent_pdf
          FROM disclosure d
         WHERE d.tenant_id = NEW.tenant_id AND d.disclosure_id = NEW.disclosure_id
           FOR SHARE;
        IF parent_status IS NULL OR parent_status NOT IN ('SEALED', 'PARTIALLY_SIGNED') THEN
            RAISE EXCEPTION 'sign_session for disclosure % in status % cannot be issued', NEW.disclosure_id, parent_status
                USING ERRCODE = 'GD101';
        END IF;
        IF NEW.signed_doc_hash IS DISTINCT FROM parent_doc OR NEW.signed_pdf_hash IS DISTINCT FROM parent_pdf THEN
            RAISE EXCEPTION 'sign_session of disclosure % must pin its canonical and PDF hashes', NEW.disclosure_id
                USING ERRCODE = 'GD101';
        END IF;
        IF NEW.status <> 'OPEN' OR NEW.identity_failures <> 0 OR cardinality(NEW.identity_passed) <> 0
            OR NEW.view_evidence IS NOT NULL OR NEW.sent_at IS NOT NULL THEN
            RAISE EXCEPTION 'sign_session % starts OPEN with no identity results, view evidence or send time', NEW.session_id
                USING ERRCODE = 'GD101';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE
    IF (SELECT jsonb_object_agg(k, to_jsonb(NEW) -> k) FROM unnest(fixed) k)
       IS DISTINCT FROM (SELECT jsonb_object_agg(k, to_jsonb(OLD) -> k) FROM unnest(fixed) k) THEN
        RAISE EXCEPTION 'sign_session % identity and pinned columns are immutable', OLD.session_id
            USING ERRCODE = 'GD101';
    END IF;
    IF OLD.status <> 'OPEN' THEN
        RAISE EXCEPTION 'sign_session % is % and no longer changes', OLD.session_id, OLD.status
            USING ERRCODE = 'GD101';
    END IF;
    IF NEW.identity_failures NOT IN (OLD.identity_failures, OLD.identity_failures + 1)
        OR NOT (NEW.identity_passed @> OLD.identity_passed)
        OR (OLD.view_evidence IS NOT NULL AND NEW.view_evidence IS DISTINCT FROM OLD.view_evidence)
        OR (OLD.sent_at IS NOT NULL AND NEW.sent_at IS DISTINCT FROM OLD.sent_at) THEN
        RAISE EXCEPTION 'sign_session % only counts failures, adds passed methods and records view evidence and send time once',
            OLD.session_id
            USING ERRCODE = 'GD101';
    END IF;
    RETURN NEW;
END
$$;

-- document_key: 파기는 정의자 롤 + 표식('key:{disclosure}')일 때만. 3B의 소유 롤 경로(ga_shred_document_key)는 폐기한다.
CREATE OR REPLACE FUNCTION ga_document_key_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_no TEXT;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'document_key rows are never removed (%); shred the key material instead', TG_OP
            USING ERRCODE = 'GD092';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT d.disclosure_no INTO parent_no
          FROM disclosure d
         WHERE d.tenant_id = NEW.tenant_id AND d.disclosure_id = NEW.disclosure_id
           FOR SHARE;
        IF parent_no IS NULL THEN
            RAISE EXCEPTION 'document_key for disclosure % may only be created once it is sealed', NEW.disclosure_id
                USING ERRCODE = 'GD092';
        END IF;
        IF NEW.wrapped_dek IS NULL THEN
            RAISE EXCEPTION 'document_key % is created with key material', NEW.key_id
                USING ERRCODE = 'GD092';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: 파기만(감싼 키 → NULL, 파기 시각·주체 기록), ga_document_key_shred(정의자 롤 + 표식)만
    IF NOT ga_destroy_branch('key:' || OLD.disclosure_id)
        OR OLD.wrapped_dek IS NULL OR NEW.wrapped_dek IS NOT NULL
        OR (to_jsonb(NEW) - ARRAY['wrapped_dek', 'shredded_at', 'shredded_by'])
           IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['wrapped_dek', 'shredded_at', 'shredded_by']) THEN
        RAISE EXCEPTION 'document_key % changes only by shredding through ga_document_key_shred', OLD.key_id
            USING ERRCODE = 'GD092';
    END IF;
    RETURN NEW;
END
$$;
DROP FUNCTION ga_shred_document_key(TEXT, UUID, TIMESTAMPTZ, TEXT);

-- customer_ref: 파기 분기(이름·전화·생년월일 암호문, CRM ID → NULL, destroyed_at 설정), 파기된 행은 그 뒤 불변(GD113)
CREATE OR REPLACE FUNCTION ga_customer_ref_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF ga_destroy_branch('customer_ref:' || OLD.customer_ref) THEN
        IF OLD.destroyed_at IS NOT NULL OR NEW.destroyed_at IS NULL OR NEW.destroyed_by IS NULL
            OR NOT ga_nulls_only(to_jsonb(OLD) - ARRAY['destroyed_at', 'destroyed_by'],
                                 to_jsonb(NEW) - ARRAY['destroyed_at', 'destroyed_by'],
                                 ARRAY['name_enc', 'phone_enc', 'birth_date_enc', 'crm_customer_id']) THEN
            RAISE EXCEPTION 'customer_ref % destruction nulls only the designated columns and sets destroyed_at once', OLD.customer_ref
                USING ERRCODE = 'GD113';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.destroyed_at IS NOT NULL THEN
        RAISE EXCEPTION 'customer_ref % was destroyed at % and no longer changes', OLD.customer_ref, OLD.destroyed_at
            USING ERRCODE = 'GD113';
    END IF;
    IF NEW.destroyed_at IS DISTINCT FROM OLD.destroyed_at OR NEW.destroyed_by IS DISTINCT FROM OLD.destroyed_by THEN
        RAISE EXCEPTION 'customer_ref % destroyed_at is set only by ga_customer_ref_destroy', OLD.customer_ref
            USING ERRCODE = 'GD113';
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.customer_ref IS DISTINCT FROM OLD.customer_ref
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'customer_ref % identity and creation time are immutable', OLD.customer_ref
            USING ERRCODE = 'GD063';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 5. anchor — 일일 테넌트 앵커(append-only). 잎 = SHA-256(0x00 ‖ JCS(레코드)) — 키가 고정된 평탄 객체이고 값이 테넌트 ID
--    (패턴 제한)·정수·ISO 날짜·소문자 hex뿐이라 JSON 이스케이프가 생기지 않아 SQL에서 같은 바이트를 만든다(GD110).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE anchor (
    tenant_id       TEXT NOT NULL REFERENCES tenant,
    anchor_seq      BIGINT NOT NULL,
    anchor_date     DATE NOT NULL,                          -- KST 날짜
    seal_chain_seq  BIGINT NOT NULL,                        -- 봉인이 없으면 0
    seal_chain_head TEXT NOT NULL,                          -- 없으면 '0'×64
    audit_seq       BIGINT NOT NULL,                        -- 이 앵커의 ANCHOR_CREATED 감사 행 직전 머리
    audit_head      TEXT NOT NULL,
    leaf_hash       TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, anchor_seq),
    CONSTRAINT ux_anchor_date UNIQUE (tenant_id, anchor_date),
    CONSTRAINT ck_anchor_tenant_format CHECK (tenant_id ~ '^[A-Z0-9][A-Z0-9_]{0,31}$'),
    CONSTRAINT ck_anchor_numbers CHECK (anchor_seq >= 1 AND seal_chain_seq >= 0 AND audit_seq >= 0),
    CONSTRAINT ck_anchor_hashes CHECK (seal_chain_head ~ '^[0-9a-f]{64}$' AND audit_head ~ '^[0-9a-f]{64}$'
        AND leaf_hash ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION ga_anchor_leaf(p_tenant TEXT, p_seq BIGINT, p_date DATE, p_seal_seq BIGINT, p_seal_head TEXT,
                               p_audit_seq BIGINT, p_audit_head TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE
AS $$
    SELECT encode(sha256('\x00'::bytea || convert_to(
        '{"anchorDate":"' || to_char(p_date, 'YYYY-MM-DD') || '","anchorSeq":' || p_seq
        || ',"auditHead":"' || p_audit_head || '","auditSeq":' || p_audit_seq
        || ',"sealChainHead":"' || p_seal_head || '","sealChainSeq":' || p_seal_seq
        || ',"tenantId":"' || p_tenant || '","v":1}', 'UTF8')), 'hex')
$$;

CREATE FUNCTION ga_anchor_guard_insert() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    zero CONSTANT TEXT := repeat('0', 64);
    prev_seq       BIGINT;
    prev_date      DATE;
    prev_seal_seq  BIGINT;
    actual         TEXT;
BEGIN
    SELECT a.anchor_seq, a.anchor_date, a.seal_chain_seq INTO prev_seq, prev_date, prev_seal_seq
      FROM anchor a
     WHERE a.tenant_id = NEW.tenant_id
     ORDER BY a.anchor_seq DESC
     LIMIT 1;
    IF NEW.anchor_seq <> coalesce(prev_seq, 0) + 1 THEN
        RAISE EXCEPTION 'anchor % of tenant % must follow % without a gap', NEW.anchor_seq, NEW.tenant_id, coalesce(prev_seq, 0)
            USING ERRCODE = 'GD110';
    END IF;
    IF prev_date IS NOT NULL AND (NEW.anchor_date <= prev_date OR NEW.seal_chain_seq < prev_seal_seq) THEN
        RAISE EXCEPTION 'anchor dates and seal chain positions only move forward for tenant %', NEW.tenant_id
            USING ERRCODE = 'GD110';
    END IF;
    IF NEW.seal_chain_seq = 0 THEN
        actual := zero;
    ELSE
        SELECT d.chain_hash INTO actual
          FROM disclosure d
         WHERE d.tenant_id = NEW.tenant_id AND d.chain_seq = NEW.seal_chain_seq;
    END IF;
    IF actual IS DISTINCT FROM NEW.seal_chain_head THEN
        RAISE EXCEPTION 'anchor seal chain head of tenant % does not match chain_seq %', NEW.tenant_id, NEW.seal_chain_seq
            USING ERRCODE = 'GD110';
    END IF;
    IF NEW.audit_seq = 0 THEN
        actual := zero;
    ELSE
        SELECT l.entry_hash INTO actual
          FROM audit_log l
         WHERE l.tenant_id = NEW.tenant_id AND l.seq = NEW.audit_seq;
    END IF;
    IF actual IS DISTINCT FROM NEW.audit_head THEN
        RAISE EXCEPTION 'anchor audit head of tenant % does not match seq %', NEW.tenant_id, NEW.audit_seq
            USING ERRCODE = 'GD110';
    END IF;
    IF NEW.leaf_hash <> ga_anchor_leaf(NEW.tenant_id, NEW.anchor_seq, NEW.anchor_date, NEW.seal_chain_seq, NEW.seal_chain_head,
                                       NEW.audit_seq, NEW.audit_head) THEN
        RAISE EXCEPTION 'anchor leaf of tenant % seq % is not SHA-256(0x00 || JCS(record))', NEW.tenant_id, NEW.anchor_seq
            USING ERRCODE = 'GD110';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_anchor_guard_insert
    BEFORE INSERT ON anchor
    FOR EACH ROW EXECUTE FUNCTION ga_anchor_guard_insert();
CREATE TRIGGER trg_anchor_append_only
    BEFORE UPDATE OR DELETE ON anchor
    FOR EACH ROW EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_anchor_no_truncate
    BEFORE TRUNCATE ON anchor
    FOR EACH STATEMENT EXECUTE FUNCTION ga_append_only();

-- ---------------------------------------------------------------------------------------------
-- 6. anchor_receipt — 앵커당 최대 1행(append-only). 노드 = SHA-256(0x01 ‖ 왼쪽 ‖ 오른쪽), 경로는 잎 → 루트 순서의 형제 해시,
--    레벨 i의 형제 방향은 leaf_index의 i번째 비트(0이면 형제가 오른쪽). DB가 루트를 다시 계산한다(GD111).
--    토큰 서명은 앱이 저장 전에, verify가 다시 검증한다.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE anchor_receipt (
    tenant_id      TEXT NOT NULL,
    anchor_seq     BIGINT NOT NULL,
    batch_id       UUID NOT NULL,
    root_hash      TEXT NOT NULL,
    tree_depth     SMALLINT NOT NULL,
    leaf_index     INTEGER NOT NULL,
    merkle_path    JSONB NOT NULL,
    tsa_token      BYTEA NOT NULL,
    tsa_gen_time   TIMESTAMPTZ NOT NULL,
    tsa_policy_oid TEXT NOT NULL,
    tsa_serial     TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, anchor_seq),
    FOREIGN KEY (tenant_id, anchor_seq) REFERENCES anchor,
    CONSTRAINT ck_anchor_receipt_root CHECK (root_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_anchor_receipt_depth CHECK (tree_depth BETWEEN 1 AND 24 AND leaf_index >= 0),
    CONSTRAINT ck_anchor_receipt_tsa CHECK (tsa_policy_oid ~ '^[0-9]+(\.[0-9]+)+$' AND tsa_serial ~ '^[0-9a-f]+$'
        AND length(tsa_token) > 0)
);

CREATE FUNCTION ga_anchor_receipt_guard_insert() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    leaf  TEXT;
    node  BYTEA;
    sib   TEXT;
    i     INT;
BEGIN
    IF NEW.leaf_index >= (1::BIGINT << NEW.tree_depth) OR jsonb_typeof(NEW.merkle_path) <> 'array'
        OR jsonb_array_length(NEW.merkle_path) <> NEW.tree_depth THEN
        RAISE EXCEPTION 'receipt of tenant % anchor % needs a path of exactly % sibling hashes and an index below 2^depth',
            NEW.tenant_id, NEW.anchor_seq, NEW.tree_depth
            USING ERRCODE = 'GD111';
    END IF;
    SELECT a.leaf_hash INTO leaf FROM anchor a WHERE a.tenant_id = NEW.tenant_id AND a.anchor_seq = NEW.anchor_seq;
    node := decode(leaf, 'hex');
    FOR i IN 0 .. NEW.tree_depth - 1 LOOP
        sib := NEW.merkle_path ->> i;
        IF sib IS NULL OR sib !~ '^[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'receipt path element % is not a SHA-256 hex', i USING ERRCODE = 'GD111';
        END IF;
        IF ((NEW.leaf_index >> i) & 1) = 0 THEN
            node := sha256('\x01'::bytea || node || decode(sib, 'hex'));
        ELSE
            node := sha256('\x01'::bytea || decode(sib, 'hex') || node);
        END IF;
    END LOOP;
    IF encode(node, 'hex') <> NEW.root_hash THEN
        RAISE EXCEPTION 'receipt of tenant % anchor %: leaf + path does not reach the root', NEW.tenant_id, NEW.anchor_seq
            USING ERRCODE = 'GD111';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_anchor_receipt_guard_insert
    BEFORE INSERT ON anchor_receipt
    FOR EACH ROW EXECUTE FUNCTION ga_anchor_receipt_guard_insert();
CREATE TRIGGER trg_anchor_receipt_append_only
    BEFORE UPDATE OR DELETE ON anchor_receipt
    FOR EACH ROW EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_anchor_receipt_no_truncate
    BEFORE TRUNCATE ON anchor_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION ga_append_only();

-- ---------------------------------------------------------------------------------------------
-- 7. legal_hold — 대상은 확인서 또는 고객 정확히 하나, 대상당 활성 1건, PLACED → RELEASED 1회(GD112). 보존기한은 바꾸지 않는다.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE legal_hold (
    tenant_id           TEXT NOT NULL REFERENCES tenant,
    hold_id             UUID NOT NULL,
    disclosure_id       UUID,
    customer_ref        TEXT,
    reason_code         TEXT NOT NULL,
    reason_text         TEXT,
    placed_by           TEXT NOT NULL,
    placed_at           TIMESTAMPTZ NOT NULL,
    released_by         TEXT,
    released_at         TIMESTAMPTZ,
    release_reason_code TEXT,
    PRIMARY KEY (tenant_id, hold_id),
    FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure,
    FOREIGN KEY (tenant_id, customer_ref) REFERENCES customer_ref,
    CONSTRAINT ck_legal_hold_target CHECK ((disclosure_id IS NULL) <> (customer_ref IS NULL)),
    CONSTRAINT ck_legal_hold_release CHECK ((released_at IS NULL) = (released_by IS NULL)
        AND (released_at IS NULL) = (release_reason_code IS NULL)),
    CONSTRAINT ck_legal_hold_text CHECK (btrim(reason_code) <> '' AND btrim(placed_by) <> ''
        AND (reason_text IS NULL OR btrim(reason_text) <> '') AND (released_by IS NULL OR btrim(released_by) <> ''))
);
CREATE UNIQUE INDEX ux_legal_hold_active_disclosure ON legal_hold (tenant_id, disclosure_id)
    WHERE released_at IS NULL AND disclosure_id IS NOT NULL;
CREATE UNIQUE INDEX ux_legal_hold_active_customer ON legal_hold (tenant_id, customer_ref)
    WHERE released_at IS NULL AND customer_ref IS NOT NULL;

CREATE FUNCTION ga_legal_hold_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'legal_hold rows are never removed (%); release the hold instead', TG_OP USING ERRCODE = 'GD112';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.released_at IS NOT NULL THEN
            RAISE EXCEPTION 'legal hold % starts PLACED', NEW.hold_id USING ERRCODE = 'GD112';
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

CREATE TRIGGER trg_legal_hold_guard
    BEFORE INSERT OR UPDATE OR DELETE ON legal_hold
    FOR EACH ROW EXECUTE FUNCTION ga_legal_hold_guard();
CREATE TRIGGER trg_legal_hold_no_truncate
    BEFORE TRUNCATE ON legal_hold
    FOR EACH STATEMENT EXECUTE FUNCTION ga_legal_hold_guard();

-- ---------------------------------------------------------------------------------------------
-- 8. 파기 함수 — SECURITY DEFINER, 정의자 disclosure_destroy_definer(테이블 소유자 아님, RLS를 그대로 따른다).
--    테넌트는 바꾸지 않고 호출 트랜잭션의 바인딩을 단언한다. 판정 실패는 GD114(트랜잭션 전체 롤백 — 감사·아웃박스도 남지 않는다).
--    p_as_of = 판정 KST 날짜(보존기한 당일이 끝난 뒤 = retention_until < p_as_of), p_at = 기록 시각(주입된 시계).
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION ga_destroy_preconditions(p_tenant TEXT, p_disclosure UUID, p_as_of DATE) RETURNS VOID
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
    IF EXISTS (SELECT 1 FROM legal_hold h
                WHERE h.tenant_id = p_tenant AND h.released_at IS NULL
                  AND (h.disclosure_id = p_disclosure OR h.customer_ref = d.customer_ref)) THEN
        RAISE EXCEPTION 'disclosure % is under legal hold', p_disclosure USING ERRCODE = 'GD114';
    END IF;
END
$$;

CREATE FUNCTION ga_document_key_shred(p_tenant TEXT, p_disclosure UUID, p_as_of DATE, p_at TIMESTAMPTZ, p_by TEXT) RETURNS TEXT
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    shredded TEXT;
BEGIN
    IF p_tenant IS NULL OR p_disclosure IS NULL OR p_as_of IS NULL OR p_at IS NULL OR p_by IS NULL OR btrim(p_by) = '' THEN
        RAISE EXCEPTION 'tenant, disclosure, judgement date, time and actor are required' USING ERRCODE = '22004';
    END IF;
    PERFORM ga_destroy_preconditions(p_tenant, p_disclosure, p_as_of);
    PERFORM set_config('ga.destroy', 'key:' || p_disclosure, true);
    UPDATE document_key
       SET wrapped_dek = NULL, shredded_at = p_at, shredded_by = p_by
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure AND wrapped_dek IS NOT NULL
    RETURNING key_id INTO shredded;
    PERFORM set_config('ga.destroy', '', true);
    IF shredded IS NULL THEN
        RAISE EXCEPTION 'disclosure % has no live document key to shred', p_disclosure USING ERRCODE = 'GD114';
    END IF;
    RETURN shredded;
END
$$;

CREATE FUNCTION ga_disclosure_destroy(p_tenant TEXT, p_disclosure UUID, p_as_of DATE, p_at TIMESTAMPTZ, p_by TEXT) RETURNS VOID
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
BEGIN
    IF p_tenant IS NULL OR p_disclosure IS NULL OR p_as_of IS NULL OR p_at IS NULL OR p_by IS NULL OR btrim(p_by) = '' THEN
        RAISE EXCEPTION 'tenant, disclosure, judgement date, time and actor are required' USING ERRCODE = '22004';
    END IF;
    PERFORM ga_destroy_preconditions(p_tenant, p_disclosure, p_as_of);
    IF EXISTS (SELECT 1 FROM document_key k
                WHERE k.tenant_id = p_tenant AND k.disclosure_id = p_disclosure AND k.wrapped_dek IS NOT NULL) THEN
        RAISE EXCEPTION 'disclosure % document key must be shredded first', p_disclosure USING ERRCODE = 'GD114';
    END IF;
    PERFORM set_config('ga.destroy', 'disclosure:' || p_disclosure, true);
    UPDATE recommendation SET reason_text = NULL
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure AND reason_text IS NOT NULL;
    UPDATE review SET reason = NULL
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure AND reason IS NOT NULL;
    UPDATE signature SET device = NULL, ip = NULL, view_evidence = NULL
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure
       AND (device IS NOT NULL OR ip IS NOT NULL OR view_evidence IS NOT NULL);
    UPDATE sign_session SET view_evidence = NULL
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure AND view_evidence IS NOT NULL;
    UPDATE compliance_flag SET policy_no = NULL
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure AND policy_no IS NOT NULL;
    UPDATE disclosure
       SET void_reason_text = NULL, supersede_reason_text = NULL, policy_no = NULL, destroyed_at = p_at, destroyed_by = p_by
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure;
    PERFORM set_config('ga.destroy', '', true);
END
$$;

CREATE FUNCTION ga_customer_ref_destroy(p_tenant TEXT, p_customer_ref TEXT, p_at TIMESTAMPTZ, p_by TEXT) RETURNS VOID
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    found_at TIMESTAMPTZ;
    found    BOOLEAN;
BEGIN
    IF p_tenant IS NULL OR p_customer_ref IS NULL OR p_at IS NULL OR p_by IS NULL OR btrim(p_by) = '' THEN
        RAISE EXCEPTION 'tenant, customer, time and actor are required' USING ERRCODE = '22004';
    END IF;
    IF p_tenant IS DISTINCT FROM current_setting('app.tenant_id', true) THEN
        RAISE EXCEPTION 'destruction runs in the bound tenant only' USING ERRCODE = 'GD114';
    END IF;
    SELECT true, c.destroyed_at INTO found, found_at
      FROM customer_ref c
     WHERE c.tenant_id = p_tenant AND c.customer_ref = p_customer_ref
       FOR UPDATE;
    IF found IS NULL THEN
        RAISE EXCEPTION 'customer_ref % not found', p_customer_ref USING ERRCODE = 'GD114';
    END IF;
    IF found_at IS NOT NULL THEN
        RAISE EXCEPTION 'customer_ref % is already destroyed', p_customer_ref USING ERRCODE = 'GD114';
    END IF;
    IF EXISTS (SELECT 1 FROM disclosure x
                WHERE x.tenant_id = p_tenant AND x.customer_ref = p_customer_ref AND x.destroyed_at IS NULL) THEN
        RAISE EXCEPTION 'customer_ref % still has live disclosures', p_customer_ref USING ERRCODE = 'GD114';
    END IF;
    IF EXISTS (SELECT 1 FROM legal_hold h
                WHERE h.tenant_id = p_tenant AND h.released_at IS NULL AND h.customer_ref = p_customer_ref) THEN
        RAISE EXCEPTION 'customer_ref % is under legal hold', p_customer_ref USING ERRCODE = 'GD114';
    END IF;
    PERFORM set_config('ga.destroy', 'customer_ref:' || p_customer_ref, true);
    UPDATE customer_ref
       SET name_enc = NULL, phone_enc = NULL, birth_date_enc = NULL, crm_customer_id = NULL, destroyed_at = p_at, destroyed_by = p_by
     WHERE tenant_id = p_tenant AND customer_ref = p_customer_ref;
    PERFORM set_config('ga.destroy', '', true);
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 9. RLS(V2와 같은 정책)·권한
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['anchor', 'anchor_receipt', 'legal_hold']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I '
            'USING (tenant_id = current_setting(''app.tenant_id'', true)) '
            'WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true))', t);
        EXECUTE format('REVOKE ALL ON TABLE %I FROM PUBLIC', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT ON TABLE anchor TO disclosure_app;
GRANT SELECT, INSERT ON TABLE anchor_receipt TO disclosure_app;
GRANT SELECT, INSERT ON TABLE legal_hold TO disclosure_app;
GRANT UPDATE (released_by, released_at, release_reason_code) ON TABLE legal_hold TO disclosure_app;
-- 파기·고객 파기 컬럼은 앱이 쓰지 않는다(함수만). customer_ref의 나머지 갱신 권한은 V2 그대로(트리거가 파기 뒤 변경을 막는다).

-- 정의자 롤: 판정에 필요한 읽기 + 지정 컬럼의 갱신만(테이블 소유자 아님 → RLS 그대로)
GRANT SELECT ON TABLE disclosure, document_key, legal_hold, recommendation, review, signature, sign_session, compliance_flag,
    customer_ref TO disclosure_destroy_definer;
GRANT UPDATE (void_reason_text, supersede_reason_text, policy_no, destroyed_at, destroyed_by) ON TABLE disclosure
    TO disclosure_destroy_definer;
GRANT UPDATE (wrapped_dek, shredded_at, shredded_by) ON TABLE document_key TO disclosure_destroy_definer;
GRANT UPDATE (reason_text) ON TABLE recommendation TO disclosure_destroy_definer;
GRANT UPDATE (reason) ON TABLE review TO disclosure_destroy_definer;
GRANT UPDATE (device, ip, view_evidence) ON TABLE signature TO disclosure_destroy_definer;
GRANT UPDATE (view_evidence) ON TABLE sign_session TO disclosure_destroy_definer;
GRANT UPDATE (policy_no) ON TABLE compliance_flag TO disclosure_destroy_definer;
GRANT UPDATE (name_enc, phone_enc, birth_date_enc, crm_customer_id, destroyed_at, destroyed_by) ON TABLE customer_ref
    TO disclosure_destroy_definer;

REVOKE ALL ON FUNCTION ga_destroy_branch(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_nulls_only(JSONB, JSONB, TEXT[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_destroy_preconditions(TEXT, UUID, DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ga_destroy_branch(TEXT) TO disclosure_destroy_definer, disclosure_app;
GRANT EXECUTE ON FUNCTION ga_nulls_only(JSONB, JSONB, TEXT[]) TO disclosure_destroy_definer, disclosure_app;
GRANT EXECUTE ON FUNCTION ga_destroy_preconditions(TEXT, UUID, DATE) TO disclosure_destroy_definer;
-- V7 ck_disclosure_seal_by_status가 부르는 순수 상태 판정(CHECK는 실행 롤의 권한으로 평가된다)
GRANT EXECUTE ON FUNCTION ga_is_mutable_status(TEXT) TO disclosure_destroy_definer;
REVOKE ALL ON FUNCTION ga_anchor_leaf(TEXT, BIGINT, DATE, BIGINT, TEXT, BIGINT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ga_anchor_leaf(TEXT, BIGINT, DATE, BIGINT, TEXT, BIGINT, TEXT) TO disclosure_app;   -- 삽입 트리거가 앱 롤로 부른다
REVOKE ALL ON FUNCTION ga_anchor_guard_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_anchor_receipt_guard_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_legal_hold_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_signature_guard_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_review_guard_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_review_reason_required() FROM PUBLIC;

-- 파기 함수 3개: 실행은 파기자 롤만, 소유(정의자)는 전용 롤. 정의자가 소유자가 되려면 스키마 CREATE가 잠시 필요하다.
REVOKE ALL ON FUNCTION ga_document_key_shred(TEXT, UUID, DATE, TIMESTAMPTZ, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_disclosure_destroy(TEXT, UUID, DATE, TIMESTAMPTZ, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_customer_ref_destroy(TEXT, TEXT, TIMESTAMPTZ, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ga_document_key_shred(TEXT, UUID, DATE, TIMESTAMPTZ, TEXT) TO disclosure_destroyer;
GRANT EXECUTE ON FUNCTION ga_disclosure_destroy(TEXT, UUID, DATE, TIMESTAMPTZ, TEXT) TO disclosure_destroyer;
GRANT EXECUTE ON FUNCTION ga_customer_ref_destroy(TEXT, TEXT, TIMESTAMPTZ, TEXT) TO disclosure_destroyer;
GRANT USAGE ON SCHEMA public TO disclosure_destroy_definer, disclosure_destroyer;
GRANT CREATE ON SCHEMA public TO disclosure_destroy_definer;
ALTER FUNCTION ga_document_key_shred(TEXT, UUID, DATE, TIMESTAMPTZ, TEXT) OWNER TO disclosure_destroy_definer;
ALTER FUNCTION ga_disclosure_destroy(TEXT, UUID, DATE, TIMESTAMPTZ, TEXT) OWNER TO disclosure_destroy_definer;
ALTER FUNCTION ga_customer_ref_destroy(TEXT, TEXT, TIMESTAMPTZ, TEXT) OWNER TO disclosure_destroy_definer;
REVOKE CREATE ON SCHEMA public FROM disclosure_destroy_definer;
