-- =============================================================================================
-- V11: legal_hold.reason_text 파기(5 계획 승인 Q4 — 구현 중 찾은 개인정보 컬럼은 표에 먼저 넣고 함수가 따라간다).
--   법적 보류 사유 텍스트는 운영자가 쓰는 자유 텍스트라 고객 사정을 담을 수 있다(경계 사례는 지우는 쪽). V9 표(설계서 §9 pii-columns)와
--   파기 함수가 빠뜨렸다 — 기존 마이그레이션은 고치지 않으므로 V11로 더한다.
--   · 해제된 보류만 지운다(활성 보류가 있으면 파기 자체가 GD114). 확인서 보류는 ga_disclosure_destroy, 고객 보류는 ga_customer_ref_destroy.
--   · 행·사유 코드·설정·해제 기록은 남는다(GD112 — 행은 지워지지 않는다).
--   · ga_legal_hold_guard에 파기 분기: 정의자 롤 + 그 확인서·고객을 가리키는 표식(ga.destroy) + 해제된 행 + reason_text만 NULL.
--   · 함수 교체는 소유자(정의자 롤)로 한다. 권한(EXECUTE는 파기자 롤만)은 CREATE OR REPLACE가 유지한다.
-- =============================================================================================

CREATE OR REPLACE FUNCTION ga_legal_hold_guard() RETURNS trigger
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

GRANT UPDATE (reason_text) ON TABLE legal_hold TO disclosure_destroy_definer;

GRANT CREATE ON SCHEMA public TO disclosure_destroy_definer;
SET ROLE disclosure_destroy_definer;

CREATE OR REPLACE FUNCTION ga_disclosure_destroy(p_tenant TEXT, p_disclosure UUID, p_as_of DATE, p_at TIMESTAMPTZ, p_by TEXT) RETURNS VOID
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
    UPDATE legal_hold SET reason_text = NULL
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure AND released_at IS NOT NULL AND reason_text IS NOT NULL;
    UPDATE disclosure
       SET void_reason_text = NULL, supersede_reason_text = NULL, policy_no = NULL, destroyed_at = p_at, destroyed_by = p_by
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure;
    PERFORM set_config('ga.destroy', '', true);
END
$$;

CREATE OR REPLACE FUNCTION ga_customer_ref_destroy(p_tenant TEXT, p_customer_ref TEXT, p_at TIMESTAMPTZ, p_by TEXT) RETURNS VOID
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
    UPDATE legal_hold SET reason_text = NULL
     WHERE tenant_id = p_tenant AND customer_ref = p_customer_ref AND released_at IS NOT NULL AND reason_text IS NOT NULL;
    UPDATE customer_ref
       SET name_enc = NULL, phone_enc = NULL, birth_date_enc = NULL, crm_customer_id = NULL, destroyed_at = p_at, destroyed_by = p_by
     WHERE tenant_id = p_tenant AND customer_ref = p_customer_ref;
    PERFORM set_config('ga.destroy', '', true);
END
$$;

RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM disclosure_destroy_definer;
