-- =============================================================================================
-- V19: 계약 연결의 이전(6B 중간 회신 ③, 2026-10-09 답변). 계약 연결은 서명처럼 문서에 귀속된 진술이 아니라 외부 사실이다.
--   · 정정 새 버전이 봉인될 때 선행 버전의 활성 연결을 이월하고(봉인 트랜잭션 — 초안에는 연결이 없다는 V15 GD130은 그대로),
--     무효·정정·만료된 확인서가 쥔 연결은 같은 청약의 새 확인서가 인수한다(같은 테넌트·같은 고객 가명).
--   · contract_link.carried_to: 다른 확인서의 새 연결 행(지연 외래키). 행은 superseded_by(같은 확인서) 또는 carried_to(다른 확인서) 중
--     하나로 한 번 닫힌다. 활성 = 둘 다 NULL. 증권 부분 유일은 활성 행만.
--   · 확인서마다 연결 사슬의 끝(superseded_by IS NULL)은 정확히 1행이고 확인서 현재값(policy_no·contract_date)은 그 행과 같다(GD136) — 끝 행이
--     이전된 행이면 옛 확인서의 현재값은 넘긴 시점의 값으로 남는다(이력, 보존·파기 판정 무변경). 이전 대상은 다른 확인서의 같은 증권번호 행이다.
-- 기존 V* 파일은 고치지 않는다 — 함수는 CREATE OR REPLACE, 트리거·인덱스는 다시 만든다.
-- =============================================================================================

ALTER TABLE contract_link
    ADD COLUMN carried_to UUID,
    ADD COLUMN carried_at TIMESTAMPTZ;
ALTER TABLE contract_link ADD CONSTRAINT ck_contract_link_carried CHECK (
    (carried_to IS NULL) = (carried_at IS NULL) AND carried_to IS DISTINCT FROM link_id AND (carried_to IS NULL OR superseded_by IS NULL));
ALTER TABLE contract_link ADD CONSTRAINT fk_contract_link_carried_to FOREIGN KEY (tenant_id, carried_to)
    REFERENCES contract_link (tenant_id, link_id) DEFERRABLE INITIALLY DEFERRED;

DROP INDEX ux_contract_link_active_policy;
CREATE UNIQUE INDEX ux_contract_link_active_policy ON contract_link (tenant_id, policy_no)
    WHERE superseded_by IS NULL AND carried_to IS NULL AND policy_no IS NOT NULL;
-- ux_contract_link_active (tenant, disclosure) WHERE superseded_by IS NULL 은 "사슬의 끝 1행"으로 그대로 쓴다.

-- 가드(V15 판 + 이전으로 닫기)
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
        IF NEW.superseded_by IS NOT NULL OR NEW.carried_to IS NOT NULL OR NEW.policy_no IS NULL THEN
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
    IF OLD.superseded_by IS NOT NULL OR OLD.carried_to IS NOT NULL
        OR (to_jsonb(NEW) - ARRAY['superseded_by', 'superseded_at', 'carried_to', 'carried_at'])
           IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['superseded_by', 'superseded_at', 'carried_to', 'carried_at'])
        OR (NEW.superseded_by IS NULL) = (NEW.carried_to IS NULL) THEN
        RAISE EXCEPTION 'contract link % closes once — superseded by a link of the same disclosure or carried to another disclosure', OLD.link_id
            USING ERRCODE = 'GD130';
    END IF;
    RETURN NEW;
END
$$;

-- 커밋 때 정합(V15 판 + 사슬 끝·이전 대상)
CREATE OR REPLACE FUNCTION ga_contract_link_mirror_check() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    tails       INTEGER;
    tail_policy TEXT;
    tail_date   DATE;
    cur_policy  TEXT;
    cur_date    DATE;
BEGIN
    SELECT count(*), max(l.policy_no), max(l.contract_date) INTO tails, tail_policy, tail_date
      FROM contract_link l
     WHERE l.tenant_id = NEW.tenant_id AND l.disclosure_id = NEW.disclosure_id AND l.superseded_by IS NULL;
    SELECT d.policy_no, d.contract_date INTO cur_policy, cur_date
      FROM disclosure d
     WHERE d.tenant_id = NEW.tenant_id AND d.disclosure_id = NEW.disclosure_id;
    IF tails <> 1 OR cur_policy IS DISTINCT FROM tail_policy OR cur_date IS DISTINCT FROM tail_date THEN
        RAISE EXCEPTION 'disclosure % must end the transaction with one link chain end mirrored in policy_no and contract_date (ends %)',
            NEW.disclosure_id, tails USING ERRCODE = 'GD136';
    END IF;
    IF NEW.carried_to IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM contract_link t
             WHERE t.tenant_id = NEW.tenant_id AND t.link_id = NEW.carried_to
               AND t.disclosure_id <> NEW.disclosure_id AND t.policy_no IS NOT DISTINCT FROM NEW.policy_no) THEN
        RAISE EXCEPTION 'contract link % is carried only to a link of another disclosure with the same policy number', NEW.link_id
            USING ERRCODE = 'GD136';
    END IF;
    RETURN NULL;
END
$$;

DROP TRIGGER trg_contract_link_mirror_supersede ON contract_link;
CREATE CONSTRAINT TRIGGER trg_contract_link_mirror_supersede
    AFTER UPDATE ON contract_link
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.superseded_by IS DISTINCT FROM NEW.superseded_by OR OLD.carried_to IS DISTINCT FROM NEW.carried_to)
    EXECUTE FUNCTION ga_contract_link_mirror_check();

-- 앱 롤은 V14부터 contract_link 전체 UPDATE를 가진다(가드가 바꿀 수 있는 컬럼을 정한다).
