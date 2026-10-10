-- =============================================================================================
-- V21: Phase 8 1a — 테넌트별 KEK(8 계획 승인 Q2, 2026-10-10).
--   설계서는 Phase 2부터 "테넌트 KEK"라고 썼지만 구현은 전역 KEK 하나였다(테넌트는 감싸기 AAD로만 묶였다 — Phase 2~7 심사 누락).
--   1. tenant_kek 레지스트리: 테넌트 → KEK ID(형식 {tenant}-KEK-{n}), 상태 CURRENT(테넌트당 정확히 하나)·RETIRED. 키 바이트는 DB에 없다(SecretSource).
--      append + CURRENT→RETIRED 한 번만(GD140).
--   2. 재래핑 함수 ga_kek_rewrap: 감싼 키를 다른 KEK로 다시 감싼 바이트로 바꾸는 유일한 경로. 정의자 롤(disclosure_destroy_definer) 소유,
--      실행은 앱 롤. 전제(테넌트 바인딩·대상 KEK가 그 테넌트의 CURRENT·이전 KEK ≠ 대상·살아 있는 키)를 함수가 판정한다(GD141).
--      불변 트리거 셋(document_key·customer_data_key·async_job)은 "정의자 롤 + 함수가 세운 표식(ga.rewrap)"일 때만 KEK ID와 감싼 바이트 두 컬럼을
--      함께 바꾸게 한다 — 파기 분기(ga.destroy)와 같은 장치. DB는 새 바이트가 같은 DEK를 감쌌는지 알 수 없다: 그 판정은 앱(재래핑 뒤 풀어 보는 검사)과
--      verify tenant가 한다. [넓힘] 불변식 — 감싼 키 바이트가 "파기 때만 바뀐다"에서 "파기 또는 재래핑 함수로만 바뀐다"로.
--   3. 작업 종류 KEK_REWRAP.
-- 기존 V* 파일은 고치지 않는다 — 함수는 CREATE OR REPLACE, 제약은 DROP/ADD.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 1. tenant_kek
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenant_kek (
    tenant_id     TEXT        NOT NULL REFERENCES tenant (tenant_id),
    kek_id        TEXT        NOT NULL,
    status        TEXT        NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    registered_by TEXT        NOT NULL,
    retired_at    TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, kek_id),
    -- 테넌트 ID에는 하이픈이 없다(TenantId) — 접두가 곧 테넌트다. 전역 시절 ID(KEK-…)와 겹칠 수 없다.
    CONSTRAINT ck_tenant_kek_id CHECK (kek_id ~ '^[A-Z0-9][A-Z0-9_]{0,31}-KEK-[1-9][0-9]{0,5}$' AND split_part(kek_id, '-KEK-', 1) = tenant_id),
    CONSTRAINT ck_tenant_kek_status CHECK (status IN ('CURRENT', 'RETIRED')),
    CONSTRAINT ck_tenant_kek_retired CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL)),
    CONSTRAINT ck_tenant_kek_by CHECK (btrim(registered_by) <> '')
);
CREATE UNIQUE INDEX ux_tenant_kek_current ON tenant_kek (tenant_id) WHERE status = 'CURRENT';

CREATE FUNCTION ga_tenant_kek_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'tenant_kek rows are never removed (%)', TG_OP USING ERRCODE = 'GD140';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'CURRENT' THEN
            RAISE EXCEPTION 'KEK % is registered as CURRENT', NEW.kek_id USING ERRCODE = 'GD140';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT (OLD.status = 'CURRENT' AND NEW.status = 'RETIRED')
        OR (to_jsonb(NEW) - ARRAY['status', 'retired_at']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'retired_at']) THEN
        RAISE EXCEPTION 'KEK % changes only by retiring it once', OLD.kek_id USING ERRCODE = 'GD140';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_tenant_kek_guard
    BEFORE INSERT OR UPDATE OR DELETE ON tenant_kek
    FOR EACH ROW EXECUTE FUNCTION ga_tenant_kek_guard();
CREATE TRIGGER trg_tenant_kek_no_truncate
    BEFORE TRUNCATE ON tenant_kek
    FOR EACH STATEMENT EXECUTE FUNCTION ga_tenant_kek_guard();

ALTER TABLE tenant_kek ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_kek FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_kek
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
REVOKE ALL ON TABLE tenant_kek FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE tenant_kek TO disclosure_app;
GRANT UPDATE (status, retired_at) ON TABLE tenant_kek TO disclosure_app;

-- ---------------------------------------------------------------------------------------------
-- 2. 재래핑 분기·함수
-- ---------------------------------------------------------------------------------------------
-- ga_rewrap_branch(target): 정의자 롤이 재래핑 함수 안에서 세운 표식이 이 행을 가리키는가(ga_destroy_branch와 같은 판정).
CREATE FUNCTION ga_rewrap_branch(p_target TEXT) RETURNS BOOLEAN
    LANGUAGE sql STABLE
AS $$
    SELECT current_user = 'disclosure_destroy_definer' AND coalesce(current_setting('ga.rewrap', true), '') = p_target
$$;

-- document_key: V9 판에 재래핑 분기만 더했다(파기 분기·INSERT 규칙은 그대로).
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
    -- UPDATE ①: 재래핑 — 살아 있는 키의 KEK ID와 감싼 바이트만, 함께(ga_kek_rewrap)
    IF ga_rewrap_branch('document_key:' || OLD.key_id) THEN
        IF OLD.wrapped_dek IS NULL OR NEW.wrapped_dek IS NULL OR NEW.kek_key_id = OLD.kek_key_id
            OR (to_jsonb(NEW) - ARRAY['kek_key_id', 'wrapped_dek']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['kek_key_id', 'wrapped_dek']) THEN
            RAISE EXCEPTION 'document_key % rewrap changes only the KEK and the wrapped key of a live key', OLD.key_id
                USING ERRCODE = 'GD092';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE ②: 파기만(감싼 키 → NULL, 파기 시각·주체 기록), ga_document_key_shred(정의자 롤 + 표식)만
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

-- customer_data_key: V5 판에 재래핑 분기만 더했다.
CREATE OR REPLACE FUNCTION ga_customer_data_key_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF ga_rewrap_branch('customer_data_key:' || OLD.key_id) THEN
        IF OLD.status = 'DESTROYED' OR OLD.wrapped_key IS NULL OR NEW.wrapped_key IS NULL OR NEW.kek_id = OLD.kek_id
            OR (to_jsonb(NEW) - ARRAY['kek_id', 'wrapped_key']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['kek_id', 'wrapped_key']) THEN
            RAISE EXCEPTION 'customer_data_key % rewrap changes only the KEK and the wrapped key of a live key', OLD.key_id
                USING ERRCODE = 'GD060';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.key_id IS DISTINCT FROM OLD.key_id
        OR NEW.kek_id IS DISTINCT FROM OLD.kek_id OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'customer_data_key % identity, KEK and creation time are immutable', OLD.key_id
            USING ERRCODE = 'GD060';
    END IF;
    IF NOT ((OLD.status = NEW.status)
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')
            OR (OLD.status = 'RETIRED' AND NEW.status = 'DESTROYED')) THEN
        RAISE EXCEPTION 'customer_data_key % status % -> % is not a single forward step', OLD.key_id, OLD.status, NEW.status
            USING ERRCODE = 'GD060';
    END IF;
    -- 감싼 키는 DESTROY 때 NULL이 되는 것 외에는 바뀌지 않는다(재래핑은 위 분기)
    IF NEW.wrapped_key IS DISTINCT FROM OLD.wrapped_key AND NOT (OLD.status = 'RETIRED' AND NEW.status = 'DESTROYED') THEN
        RAISE EXCEPTION 'customer_data_key % wrapped key is immutable', OLD.key_id
            USING ERRCODE = 'GD060';
    END IF;
    IF NEW.status = 'DESTROYED' AND OLD.status <> 'DESTROYED' AND EXISTS (
            SELECT 1 FROM customer_ref r WHERE r.tenant_id = OLD.tenant_id AND r.enc_key_id = OLD.key_id) THEN
        RAISE EXCEPTION 'customer_data_key % still encrypts customer_ref rows; re-encrypt them before destroying it', OLD.key_id
            USING ERRCODE = 'GD061';
    END IF;
    RETURN NEW;
END
$$;

-- async_job: V12 판에 재래핑 분기만 더했다(끝난 작업 보고서 키).
CREATE OR REPLACE FUNCTION ga_async_job_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    fixed CONSTANT TEXT[] := ARRAY['tenant_id', 'job_id', 'kind', 'requested_by', 'channel', 'params', 'requested_at'];
    moved TEXT[];
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'async_job rows are never removed (%)', TG_OP USING ERRCODE = 'GD121';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'QUEUED' THEN
            RAISE EXCEPTION 'job % starts QUEUED', NEW.job_id USING ERRCODE = 'GD121';
        END IF;
        RETURN NEW;
    END IF;
    IF ga_rewrap_branch('async_job:' || OLD.job_id) THEN
        IF OLD.status <> 'SUCCEEDED' OR OLD.report_key_wrapped IS NULL OR NEW.report_key_wrapped IS NULL OR NEW.report_kek_id = OLD.report_kek_id
            OR (to_jsonb(NEW) - ARRAY['report_kek_id', 'report_key_wrapped'])
               IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['report_kek_id', 'report_key_wrapped']) THEN
            RAISE EXCEPTION 'job % rewrap changes only the report KEK and wrapped key', OLD.job_id USING ERRCODE = 'GD121';
        END IF;
        RETURN NEW;
    END IF;
    IF (SELECT jsonb_object_agg(k, to_jsonb(NEW) -> k) FROM unnest(fixed) k)
       IS DISTINCT FROM (SELECT jsonb_object_agg(k, to_jsonb(OLD) -> k) FROM unnest(fixed) k) THEN
        RAISE EXCEPTION 'job % identity, request and parameters are immutable', OLD.job_id USING ERRCODE = 'GD121';
    END IF;
    moved := CASE
        WHEN OLD.status = 'QUEUED' AND NEW.status = 'RUNNING' THEN ARRAY['status', 'started_at']
        WHEN OLD.status = 'RUNNING' AND NEW.status = 'SUCCEEDED'
            THEN ARRAY['status', 'finished_at', 'result_ref', 'report_sha256', 'report_key_wrapped', 'report_kek_id']
        WHEN OLD.status = 'RUNNING' AND NEW.status = 'FAILED' THEN ARRAY['status', 'finished_at', 'error_code']
        WHEN OLD.status = 'QUEUED' AND NEW.status = 'FAILED' THEN ARRAY['status', 'finished_at', 'error_code']
    END;
    IF moved IS NULL OR (to_jsonb(NEW) - moved) IS DISTINCT FROM (to_jsonb(OLD) - moved) THEN
        RAISE EXCEPTION 'job % cannot move % → %', OLD.job_id, OLD.status, NEW.status USING ERRCODE = 'GD121';
    END IF;
    RETURN NEW;
END
$$;

-- 재래핑: 대상 표 하나의 행 하나. 반환 = 바뀐 행이 정확히 하나인가(이미 옮겨졌거나 그 사이 파기됐으면 false — 재실행이 건너뛴다).
CREATE FUNCTION ga_kek_rewrap(p_tenant TEXT, p_target TEXT, p_key_id TEXT, p_from TEXT, p_to TEXT, p_wrapped BYTEA) RETURNS BOOLEAN
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    changed INT;
BEGIN
    IF p_tenant IS NULL OR p_target IS NULL OR p_key_id IS NULL OR p_from IS NULL OR p_to IS NULL OR p_wrapped IS NULL THEN
        RAISE EXCEPTION 'tenant, target, key, both KEKs and the wrapped key are required' USING ERRCODE = 'GD141';
    END IF;
    IF p_tenant IS DISTINCT FROM current_setting('app.tenant_id', true) THEN
        RAISE EXCEPTION 'rewrap runs inside the bound tenant' USING ERRCODE = 'GD141';
    END IF;
    IF p_from = p_to THEN
        RAISE EXCEPTION 'rewrap moves a key to a different KEK' USING ERRCODE = 'GD141';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tenant_kek k WHERE k.tenant_id = p_tenant AND k.kek_id = p_to AND k.status = 'CURRENT') THEN
        RAISE EXCEPTION 'KEK % is not the current KEK of tenant %', p_to, p_tenant USING ERRCODE = 'GD141';
    END IF;
    IF octet_length(p_wrapped) < 29 THEN                                      -- 0x01 ‖ nonce 12 ‖ … ‖ tag 16
        RAISE EXCEPTION 'wrapped key is too short' USING ERRCODE = 'GD141';
    END IF;
    PERFORM set_config('ga.rewrap', p_target || ':' || p_key_id, true);
    CASE p_target
        WHEN 'document_key' THEN
            UPDATE document_key SET kek_key_id = p_to, wrapped_dek = p_wrapped
             WHERE tenant_id = p_tenant AND key_id = p_key_id AND kek_key_id = p_from AND wrapped_dek IS NOT NULL;
        WHEN 'customer_data_key' THEN
            UPDATE customer_data_key SET kek_id = p_to, wrapped_key = p_wrapped
             WHERE tenant_id = p_tenant AND key_id = p_key_id AND kek_id = p_from AND status <> 'DESTROYED' AND wrapped_key IS NOT NULL;
        WHEN 'async_job' THEN
            UPDATE async_job SET report_kek_id = p_to, report_key_wrapped = p_wrapped
             WHERE tenant_id = p_tenant AND job_id = p_key_id::uuid AND report_kek_id = p_from AND report_key_wrapped IS NOT NULL;
        ELSE
            RAISE EXCEPTION 'unknown rewrap target %', p_target USING ERRCODE = 'GD141';
    END CASE;
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM set_config('ga.rewrap', '', true);
    RETURN changed = 1;
END
$$;

-- 정의자 롤: 대상 판정 읽기 + 두 컬럼 갱신만(테이블 소유자 아님 → RLS 그대로)
GRANT SELECT ON TABLE tenant_kek, customer_data_key, async_job TO disclosure_destroy_definer;
GRANT UPDATE (kek_key_id, wrapped_dek) ON TABLE document_key TO disclosure_destroy_definer;
GRANT UPDATE (kek_id, wrapped_key) ON TABLE customer_data_key TO disclosure_destroy_definer;
GRANT UPDATE (report_kek_id, report_key_wrapped) ON TABLE async_job TO disclosure_destroy_definer;

REVOKE ALL ON FUNCTION ga_rewrap_branch(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ga_rewrap_branch(TEXT) TO disclosure_app, disclosure_destroy_definer;
REVOKE ALL ON FUNCTION ga_kek_rewrap(TEXT, TEXT, TEXT, TEXT, TEXT, BYTEA) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ga_kek_rewrap(TEXT, TEXT, TEXT, TEXT, TEXT, BYTEA) TO disclosure_app;
GRANT CREATE ON SCHEMA public TO disclosure_destroy_definer;
ALTER FUNCTION ga_kek_rewrap(TEXT, TEXT, TEXT, TEXT, TEXT, BYTEA) OWNER TO disclosure_destroy_definer;
REVOKE CREATE ON SCHEMA public FROM disclosure_destroy_definer;
REVOKE ALL ON FUNCTION ga_tenant_kek_guard() FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- 3. 작업 종류
-- ---------------------------------------------------------------------------------------------
ALTER TABLE async_job DROP CONSTRAINT ck_job_kind;
ALTER TABLE async_job ADD CONSTRAINT ck_job_kind CHECK (kind IN ('ANCHOR', 'EXPIRE', 'RECONCILE', 'DESTROY', 'DESTROY_DRY_RUN', 'VERIFY_TENANT',
                                                                 'NOTIFY', 'IDEMPOTENCY_PURGE', 'FLAG_SLA_SWEEP', 'COLLECTION_RATE_SNAPSHOT',
                                                                 'ABANDON_DRAFTS', 'RETENTION_RECOMPUTE', 'CONTRACT_LINK_IMPORT',
                                                                 'CONTRACT_LINK_UNMATCHED_PURGE', 'KEK_REWRAP'));
