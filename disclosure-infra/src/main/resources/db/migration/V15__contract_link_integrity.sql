-- =============================================================================================
-- V15: V14 계약 연결의 무결성·파기 공백 보강(6B 2단계 커밋 325c01c 뒤 보안 점검).
--   1. 증권 현재값 정합의 반대 방향: V14 GD136은 disclosure.policy_no·contract_date를 바꿀 때만 활성 연결과 대조했다. 연결만 바꾸면(활성 행을
--      예전 행으로 대체해 활성 0건을 만들거나, 새 활성 행을 넣고 확인서를 두면) 확인서의 현재값이 연결과 어긋난 채 커밋됐다. 이제 연결의 INSERT·UPDATE마다
--      커밋 때(지연 제약 트리거) 그 확인서에 활성 연결이 정확히 1건이고 확인서의 현재값이 그 값과 같은지 본다(GD136).
--   2. 봉인 전 초안의 연결: V14는 초안에도 연결 INSERT를 허용했다. 초안이 폐기되면(ABANDONED 묘비 — 파기 대상 밖) 연결의 증권·청약 번호와 확인서
--      policy_no가 지워질 길이 없다. 연결은 봉인 이후·폐기·파기되지 않은 확인서에만 생긴다(GD130 — 유스케이스의 NOT_SEALED 보고와 같은 경계).
--   3. 확인서는 증권 현재값 없이 생긴다(연결이 먼저 있을 수 없다 — GD136).
-- 기존 V* 파일은 고치지 않는다 — 함수는 CREATE OR REPLACE.
-- =============================================================================================

-- 2. 연결 가드(V14 판 + 부모 상태)
CREATE OR REPLACE FUNCTION ga_contract_link_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    st   TEXT;
    gone TIMESTAMPTZ;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'contract_link is history: % rejected', TG_OP USING ERRCODE = 'GD130';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.superseded_by IS NOT NULL OR NEW.policy_no IS NULL THEN
            RAISE EXCEPTION 'a contract link is inserted active and with its policy number' USING ERRCODE = 'GD130';
        END IF;
        SELECT d.status, d.destroyed_at INTO st, gone
          FROM disclosure d
         WHERE d.tenant_id = NEW.tenant_id AND d.disclosure_id = NEW.disclosure_id;
        IF NOT FOUND OR ga_is_mutable_status(st) OR st = 'ABANDONED' OR gone IS NOT NULL THEN
            RAISE EXCEPTION 'a contract link is made only for a sealed, not destroyed disclosure (status %)', coalesce(st, 'none')
                USING ERRCODE = 'GD130';
        END IF;
        RETURN NEW;
    END IF;
    IF ga_destroy_branch('disclosure:' || OLD.disclosure_id) THEN
        IF NOT ga_nulls_only(to_jsonb(OLD), to_jsonb(NEW), ARRAY['policy_no', 'application_no']) THEN
            RAISE EXCEPTION 'contract link destruction nulls only policy_no and application_no' USING ERRCODE = 'GD113';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.superseded_by IS NOT NULL
        OR (to_jsonb(NEW) - ARRAY['superseded_by', 'superseded_at']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['superseded_by', 'superseded_at'])
        OR NEW.superseded_by IS NULL THEN
        RAISE EXCEPTION 'contract link % changes only by being superseded once (by a link of the same disclosure — deferred FK)', OLD.link_id
            USING ERRCODE = 'GD130';
    END IF;
    RETURN NEW;
END
$$;

-- 1. 커밋 때 정합: 연결이 생기거나 대체된 확인서마다 활성 1건 + 현재값 일치
CREATE FUNCTION ga_contract_link_mirror_check() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    actives       INTEGER;
    active_policy TEXT;
    active_date   DATE;
    cur_policy    TEXT;
    cur_date      DATE;
BEGIN
    SELECT count(*), max(l.policy_no), max(l.contract_date) INTO actives, active_policy, active_date
      FROM contract_link l
     WHERE l.tenant_id = NEW.tenant_id AND l.disclosure_id = NEW.disclosure_id AND l.superseded_by IS NULL;
    SELECT d.policy_no, d.contract_date INTO cur_policy, cur_date
      FROM disclosure d
     WHERE d.tenant_id = NEW.tenant_id AND d.disclosure_id = NEW.disclosure_id;
    IF actives <> 1 OR cur_policy IS DISTINCT FROM active_policy OR cur_date IS DISTINCT FROM active_date THEN
        RAISE EXCEPTION 'disclosure % must end the transaction with exactly one active contract link mirrored in policy_no and contract_date (active %)',
            NEW.disclosure_id, actives USING ERRCODE = 'GD136';
    END IF;
    RETURN NULL;
END
$$;
-- 파기 분기(번호 NULL)는 큐에 넣지 않는다 — 파기자 롤은 테이블을 읽지 못하고, 파기 함수가 확인서·연결의 증권번호를 함께 지운다(GD113)
CREATE CONSTRAINT TRIGGER trg_contract_link_mirror_insert
    AFTER INSERT ON contract_link
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ga_contract_link_mirror_check();
CREATE CONSTRAINT TRIGGER trg_contract_link_mirror_supersede
    AFTER UPDATE ON contract_link
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.superseded_by IS DISTINCT FROM NEW.superseded_by)
    EXECUTE FUNCTION ga_contract_link_mirror_check();

-- 3. 확인서 INSERT 가드(V14 판 + 증권 현재값 없음)
CREATE OR REPLACE FUNCTION ga_disclosure_guard_insert_abandoned() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ABANDONED' OR NEW.abandoned_at IS NOT NULL THEN
        RAISE EXCEPTION 'a disclosure is not created ABANDONED' USING ERRCODE = 'GD133';
    END IF;
    IF NEW.policy_no IS NOT NULL OR NEW.contract_date IS NOT NULL THEN
        RAISE EXCEPTION 'a disclosure is created without policy_no and contract_date (they mirror a later contract link)' USING ERRCODE = 'GD136';
    END IF;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION ga_contract_link_mirror_check() FROM PUBLIC;
