-- V17 — 초안 폐기가 법적 보류를 지킨다(6B 6단계 보안 점검).
--   V14의 ga_draft_abandon은 보류를 보지 않았다: 보류가 걸린 초안(확인서 보류 또는 그 고객 보류)의 추천사유·검토 사유·항목 입력값·청약번호를 설계사
--   명시 폐기나 방치 배치가 지울 수 있었다. 파기 전제(V9 ga_destroy_preconditions)와 같은 조건으로 거부한다(GD137). 앱도 먼저 보고 업무 거부한다.
--   함수 교체는 소유자(정의자 롤)로 한다 — 권한(EXECUTE는 disclosure_abandoner만)은 CREATE OR REPLACE가 유지한다.
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
