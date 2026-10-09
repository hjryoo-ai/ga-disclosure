-- =============================================================================================
-- V14: Phase 6B 본 DDL(6B 계획 §2·§A, 승인 2026-10-09 — 지시문의 "V13"은 6B 첫 커밋의 멱등 해제가 썼다).
--   1. 롤 확인(disclosure_abandoner) · identity_link 역할 CONTRACT_FEED·GATE_CLIENT(서비스 단독)
--   2. disclosure: application_no(작성 때만, GD132)·ABANDONED 묘비(abandoned_at, GD133)·증권 현재값 정합(GD136)
--   3. contract_link(append-only 이력, 활성 1건, GD130)·contract_link_unmatched(보고 행, GD131)
--   4. compliance_flag 확장(룰 복사 컬럼·담당자·SLA 경과·수동 해소 근거)·유형 닫힌 목록·가드(GD134 — 지금까지 없었다)
--   5. collection_rate_snapshot(append-only, GD135)
--   6. async_job 종류·아웃박스 DisclosureAbandoned
--   7. 함수: ga_draft_abandon(신설 — 초안 폐기, 정의자 롤 소유, 실행은 disclosure_abandoner만), ga_disclosure_destroy(application_no·
--      contract_link 증권·청약 번호 추가), ga_customer_ref_destroy(live 확인서에서 ABANDONED 제외), ga_review_guard_update(폐기 분기)
--   8. RLS·권한
-- 오류 코드 GD130~GD136(docs/db-error-codes.md). 기존 V* 파일은 고치지 않는다 — 함수는 CREATE OR REPLACE, 제약은 DROP/ADD.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 1. 롤 — 클러스터 수준이라 docker/postgres/init-roles.sql이 만든다(V9와 같은 방식).
-- ---------------------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'disclosure_abandoner' AND NOT rolcanlogin AND NOT rolbypassrls AND NOT rolsuper)
        OR NOT pg_has_role('disclosure_app', 'disclosure_abandoner', 'SET') THEN
        RAISE EXCEPTION 'V14 needs role disclosure_abandoner (NOLOGIN, NOBYPASSRLS, app may SET ROLE to it) — see docker/postgres/init-roles.sql';
    END IF;
END
$$;

ALTER TABLE identity_link DROP CONSTRAINT ck_identity_link_roles;
ALTER TABLE identity_link ADD CONSTRAINT ck_identity_link_roles CHECK (
    cardinality(roles) >= 1
    AND roles <@ ARRAY['AGENT', 'MANAGER', 'COMPLIANCE', 'SCHEDULER', 'FEED_CONSUMER', 'CONTRACT_FEED', 'GATE_CLIENT']::text[]);
ALTER TABLE identity_link DROP CONSTRAINT ck_identity_link_service_alone;
ALTER TABLE identity_link ADD CONSTRAINT ck_identity_link_service_alone CHECK (
    NOT (roles && ARRAY['SCHEDULER', 'FEED_CONSUMER', 'CONTRACT_FEED', 'GATE_CLIENT']::text[])
    OR (cardinality(roles) = 1 AND agent_id IS NULL AND org_path IS NULL));

-- ---------------------------------------------------------------------------------------------
-- 2. disclosure
--   application_no: 작성 때 선택 입력(청약번호 — 형식은 §14 #19, 공백 없는 1~64자만), 값의 변경은 폐기·파기 분기의 NULL뿐(GD132).
--   ABANDONED: 봉인 전 초안의 묘비. ga_draft_abandon(정의자)만 그 상태로 옮기고, 그 뒤 어떤 변경도 없다(GD133). 번호·봉인 컬럼은 없다.
--   policy_no·contract_date: contract_link 활성 행의 현재값(6B 승인 §3). 바꾸는 UPDATE는 새 값이 활성 연결과 같아야 한다(GD136) — 연결 유스케이스
--   밖의 변경은 정합 검사로 거부된다. 파기 분기의 policy_no NULL은 예외.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE disclosure
    ADD COLUMN application_no TEXT,
    ADD COLUMN abandoned_at   TIMESTAMPTZ,
    ADD CONSTRAINT ck_disclosure_application_no CHECK (application_no IS NULL OR application_no ~ '^\S{1,64}$');
ALTER TABLE disclosure DROP CONSTRAINT ck_disclosure_status;
ALTER TABLE disclosure ADD CONSTRAINT ck_disclosure_status CHECK (status IN ('DRAFT', 'COMPARED', 'GRADED', 'REASONED', 'SEALED',
                                                                             'PARTIALLY_SIGNED', 'COMPLETED', 'VOID', 'SUPERSEDED', 'EXPIRED',
                                                                             'ABANDONED'));
ALTER TABLE disclosure ADD CONSTRAINT ck_disclosure_abandoned CHECK ((status = 'ABANDONED') = (abandoned_at IS NOT NULL));
ALTER TABLE disclosure DROP CONSTRAINT ck_disclosure_seal_by_status;
ALTER TABLE disclosure ADD CONSTRAINT ck_disclosure_seal_by_status CHECK (CASE
    WHEN ga_is_mutable_status(status) THEN disclosure_no IS NULL
    WHEN status = 'ABANDONED' THEN disclosure_no IS NULL                      -- 봉인 전 초안의 묘비(번호 없음)
    WHEN status = 'VOID' THEN true
    ELSE disclosure_no IS NOT NULL END);
CREATE INDEX ix_disclosure_application_no ON disclosure (tenant_id, application_no) WHERE application_no IS NOT NULL;

-- 확인서 UPDATE 가드(V9 판 + 6B). 분기 순서: 파기 → 폐기 → 묘비(파기·폐기된 행) → 일반 규칙.
CREATE OR REPLACE FUNCTION ga_disclosure_guard_update() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    meta CONSTANT TEXT[] := ARRAY['status', 'superseded_by_id', 'completed_at', 'voided_at',
                                  'void_reason_code', 'void_reason_text', 'supersede_reason_code', 'supersede_reason_text',
                                  'policy_no', 'contract_date', 'retention_until'];
    erased CONSTANT TEXT[] := ARRAY['void_reason_text', 'supersede_reason_text', 'policy_no', 'application_no'];
    active_policy TEXT;
    active_date   DATE;
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
    IF ga_destroy_branch('abandon:' || OLD.disclosure_id) THEN
        IF NOT ga_is_mutable_status(OLD.status) OR OLD.destroyed_at IS NOT NULL OR NEW.status <> 'ABANDONED' OR NEW.abandoned_at IS NULL
            OR NOT ga_nulls_only(to_jsonb(OLD) - ARRAY['status', 'abandoned_at'], to_jsonb(NEW) - ARRAY['status', 'abandoned_at'],
                                 ARRAY['application_no']) THEN
            RAISE EXCEPTION 'disclosure % abandonment moves a draft to ABANDONED and nulls only application_no', OLD.disclosure_id
                USING ERRCODE = 'GD133';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.destroyed_at IS NOT NULL THEN
        RAISE EXCEPTION 'disclosure % was destroyed at % and no longer changes', OLD.disclosure_id, OLD.destroyed_at
            USING ERRCODE = 'GD113';
    END IF;
    IF OLD.status = 'ABANDONED' THEN
        RAISE EXCEPTION 'disclosure % was abandoned at % and no longer changes', OLD.disclosure_id, OLD.abandoned_at
            USING ERRCODE = 'GD133';
    END IF;
    IF NEW.status = 'ABANDONED' OR NEW.abandoned_at IS DISTINCT FROM OLD.abandoned_at THEN
        RAISE EXCEPTION 'disclosure % becomes ABANDONED only through ga_draft_abandon', OLD.disclosure_id
            USING ERRCODE = 'GD133';
    END IF;
    IF NEW.destroyed_at IS DISTINCT FROM OLD.destroyed_at OR NEW.destroyed_by IS DISTINCT FROM OLD.destroyed_by THEN
        RAISE EXCEPTION 'disclosure % destroyed_at is set only by ga_disclosure_destroy', OLD.disclosure_id
            USING ERRCODE = 'GD113';
    END IF;
    IF NEW.application_no IS DISTINCT FROM OLD.application_no THEN
        RAISE EXCEPTION 'disclosure % application_no is set when the draft is created', OLD.disclosure_id
            USING ERRCODE = 'GD132';
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
    -- 증권 현재값: 바뀐 컬럼은 활성 연결의 값이어야 한다(본문 가드 뒤 — 봉인 이후 본문 변경은 GD001이 먼저)
    IF NEW.policy_no IS DISTINCT FROM OLD.policy_no OR NEW.contract_date IS DISTINCT FROM OLD.contract_date THEN
        SELECT l.policy_no, l.contract_date INTO active_policy, active_date
          FROM contract_link l
         WHERE l.tenant_id = OLD.tenant_id AND l.disclosure_id = OLD.disclosure_id AND l.superseded_by IS NULL;
        IF NOT FOUND
            OR (NEW.policy_no IS DISTINCT FROM OLD.policy_no AND NEW.policy_no IS DISTINCT FROM active_policy)
            OR (NEW.contract_date IS DISTINCT FROM OLD.contract_date AND NEW.contract_date IS DISTINCT FROM active_date) THEN
            RAISE EXCEPTION 'disclosure % policy_no and contract_date mirror its active contract_link', OLD.disclosure_id
                USING ERRCODE = 'GD136';
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

-- 초안 생성 때 ABANDONED로 들어올 수 없다(상태 CHECK는 넓혔으므로 INSERT 가드를 따로 둔다)
CREATE FUNCTION ga_disclosure_guard_insert_abandoned() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ABANDONED' OR NEW.abandoned_at IS NOT NULL THEN
        RAISE EXCEPTION 'a disclosure is not created ABANDONED' USING ERRCODE = 'GD133';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_disclosure_guard_insert_abandoned
    BEFORE INSERT ON disclosure
    FOR EACH ROW EXECUTE FUNCTION ga_disclosure_guard_insert_abandoned();

-- review는 append-only(GD030) — 파기 분기(V9)에 폐기 분기를 더한다(사유 텍스트만 NULL)
CREATE OR REPLACE FUNCTION ga_review_guard_update() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (ga_destroy_branch('disclosure:' || OLD.disclosure_id) OR ga_destroy_branch('abandon:' || OLD.disclosure_id))
        AND ga_nulls_only(to_jsonb(OLD), to_jsonb(NEW), ARRAY['reason']) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'review is append-only: UPDATE rejected' USING ERRCODE = 'GD030';
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 3. 계약 연결(6B 계획 §2.1·§4, 승인 §3)
--   contract_link: 이력. 활성 = superseded_by IS NULL — 확인서당 1건, 증권당 1건. 정정은 새 행 + 이전 행에 superseded_by 1회.
--   같은 source·source_ref는 한 번(재수입 멱등). 증권·청약 번호는 파기 분기만 NULL(EXTERNAL_ID — pii-columns).
--   contract_link_unmatched: 매칭 0건·다수·봉인 전·고객 불일치의 보고 행(개인정보는 policy_no만). UPDATE 없음, 삭제는 정리 작업(룰 기간 뒤).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE contract_link (
    tenant_id      TEXT        NOT NULL REFERENCES tenant (tenant_id),
    link_id        UUID        NOT NULL,
    disclosure_id  UUID        NOT NULL,
    policy_no      TEXT,
    application_no TEXT,
    contract_date  DATE        NOT NULL,
    insurer_code   TEXT        NOT NULL,
    product_key    TEXT,
    source         TEXT        NOT NULL,
    source_ref     TEXT        NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL,
    linked_by      TEXT        NOT NULL,
    superseded_by  UUID,
    superseded_at  TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, link_id),
    FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure (tenant_id, disclosure_id),
    CONSTRAINT ck_contract_link_numbers CHECK ((policy_no IS NULL OR policy_no ~ '^\S{1,64}$')
                                               AND (application_no IS NULL OR application_no ~ '^\S{1,64}$')),
    CONSTRAINT ck_contract_link_codes CHECK (insurer_code ~ '^[A-Z0-9_]{1,32}$' AND source ~ '^[A-Z][A-Z0-9_]{0,31}$'
                                             AND btrim(source_ref) <> '' AND btrim(linked_by) <> ''),
    CONSTRAINT ck_contract_link_superseded CHECK ((superseded_by IS NULL) = (superseded_at IS NULL) AND superseded_by IS DISTINCT FROM link_id),
    CONSTRAINT ux_contract_link_disclosure_link UNIQUE (tenant_id, disclosure_id, link_id),
    -- 정정: 이전 행에 superseded_by를 먼저 쓰고 새 활성 행을 넣는다(활성 1건 부분 유일 때문에) — 가리키는 행은 같은 확인서의 연결이어야 하고 커밋 때 확인한다
    CONSTRAINT fk_contract_link_superseded_by FOREIGN KEY (tenant_id, disclosure_id, superseded_by)
        REFERENCES contract_link (tenant_id, disclosure_id, link_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE UNIQUE INDEX ux_contract_link_active ON contract_link (tenant_id, disclosure_id) WHERE superseded_by IS NULL;
CREATE UNIQUE INDEX ux_contract_link_active_policy ON contract_link (tenant_id, policy_no) WHERE superseded_by IS NULL AND policy_no IS NOT NULL;
CREATE UNIQUE INDEX ux_contract_link_source ON contract_link (tenant_id, source, source_ref);

CREATE FUNCTION ga_contract_link_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'contract_link is history: % rejected', TG_OP USING ERRCODE = 'GD130';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.superseded_by IS NOT NULL OR NEW.policy_no IS NULL THEN
            RAISE EXCEPTION 'a contract link is inserted active and with its policy number' USING ERRCODE = 'GD130';
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
CREATE TRIGGER trg_contract_link_guard
    BEFORE INSERT OR UPDATE OR DELETE ON contract_link
    FOR EACH ROW EXECUTE FUNCTION ga_contract_link_guard();
CREATE TRIGGER trg_contract_link_no_truncate
    BEFORE TRUNCATE ON contract_link
    FOR EACH STATEMENT EXECUTE FUNCTION ga_contract_link_guard();

CREATE TABLE contract_link_unmatched (
    tenant_id     TEXT        NOT NULL REFERENCES tenant (tenant_id),
    unmatched_id  UUID        NOT NULL,
    policy_no     TEXT        NOT NULL,
    contract_date DATE        NOT NULL,
    insurer_code  TEXT        NOT NULL,
    reason        TEXT        NOT NULL,
    source        TEXT        NOT NULL,
    source_ref    TEXT        NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, unmatched_id),
    CONSTRAINT ux_contract_link_unmatched_source UNIQUE (tenant_id, source, source_ref),
    CONSTRAINT ck_contract_link_unmatched_reason CHECK (reason IN ('UNMATCHED', 'AMBIGUOUS_MATCH', 'NOT_SEALED', 'CUSTOMER_MISMATCH')),
    CONSTRAINT ck_contract_link_unmatched_values CHECK (policy_no ~ '^\S{1,64}$' AND insurer_code ~ '^[A-Z0-9_]{1,32}$'
                                                        AND source ~ '^[A-Z][A-Z0-9_]{0,31}$' AND btrim(source_ref) <> '')
);
CREATE INDEX ix_contract_link_unmatched_received ON contract_link_unmatched (tenant_id, received_at);

CREATE FUNCTION ga_contract_link_unmatched_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'contract_link_unmatched rows are never changed (%); they are deleted after the rule period', TG_OP USING ERRCODE = 'GD131';
END
$$;
CREATE TRIGGER trg_contract_link_unmatched_guard
    BEFORE UPDATE ON contract_link_unmatched
    FOR EACH ROW EXECUTE FUNCTION ga_contract_link_unmatched_guard();
CREATE TRIGGER trg_contract_link_unmatched_no_truncate
    BEFORE TRUNCATE ON contract_link_unmatched
    FOR EACH STATEMENT EXECUTE FUNCTION ga_contract_link_unmatched_guard();

-- ---------------------------------------------------------------------------------------------
-- 4. compliance_flag(6B 계획 §2.3·§7). 유형은 닫힌 목록 — 룰 complianceQueue.types의 키·코드 상수와 FlagTypeTableTest가 대조한다.
--   룰에서 복사하는 값(담당 역할·설계사 가시성·기한)은 생성 때 고정. 기존 행은 fail-closed 기본값(준법·보이지 않음·기한 없음).
--   가드(GD134 — V1부터 가드가 없었다): 식별·유형·대상·열린 시각·복사 값은 고정, 해소는 한 번(시각·주체·방식 함께, 코드·근거는 그때만),
--   담당자는 열린 동안만, SLA 경과 표시는 한 번, 파기 분기는 policy_no NULL만, DELETE·TRUNCATE 없음.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE compliance_flag
    ADD COLUMN assigned_role       TEXT NOT NULL DEFAULT 'COMPLIANCE',   -- 기존 행은 이 기본값으로 채워진다(UPDATE는 FORCE RLS 아래라 쓰지 않는다)
    ADD COLUMN visible_to_agent    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN due_at              TIMESTAMPTZ,
    ADD COLUMN assignee            TEXT,
    ADD COLUMN sla_breached_at     TIMESTAMPTZ,
    ADD COLUMN resolution_code     TEXT,
    ADD COLUMN resolution_evidence JSONB;
ALTER TABLE compliance_flag
    ADD CONSTRAINT ck_compliance_flag_type CHECK (type IN (
        'GRADE_INCONSISTENT', 'VALIDATION_OVERRIDE', 'RULE_SUPERSEDED_DRAFT', 'IDENTITY_FAILED', 'SIGNATURE_DEVICE_REUSE',
        'PAPER_SCAN_REVIEW', 'SIGN_EXPIRED', 'CHAIN_BROKEN', 'NOTIFY_FAILED', 'RULE_DRIFT', 'RULE_ACTIVATION_MISSED')),
    ADD CONSTRAINT ck_compliance_flag_assigned_role CHECK (assigned_role IN ('COMPLIANCE', 'MANAGER')),
    ADD CONSTRAINT ck_compliance_flag_due CHECK (due_at IS NULL OR due_at > raised_at),
    ADD CONSTRAINT ck_compliance_flag_resolution CHECK (
        (resolved_at IS NULL) = (resolved_by IS NULL) AND (resolved_at IS NULL) = (resolution IS NULL)
        AND (resolution_code IS NULL OR (resolved_at IS NOT NULL AND resolution_code ~ '^[A-Z][A-Z0-9_]{0,39}$'))
        AND (resolution_evidence IS NULL OR (resolution_code IS NOT NULL AND jsonb_typeof(resolution_evidence) = 'object'))),
    ADD CONSTRAINT ck_compliance_flag_sla CHECK (sla_breached_at IS NULL OR (due_at IS NOT NULL AND sla_breached_at >= due_at)),
    ADD CONSTRAINT ck_compliance_flag_assignee CHECK (assignee IS NULL OR btrim(assignee) <> '');

CREATE FUNCTION ga_compliance_flag_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    fixed CONSTANT TEXT[] := ARRAY['tenant_id', 'flag_id', 'type', 'disclosure_id', 'agent_id', 'severity', 'raised_at', 'target_kind',
                                   'target_id', 'assigned_role', 'visible_to_agent', 'due_at', 'policy_no'];
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'compliance flags are never removed (%); resolve them', TG_OP USING ERRCODE = 'GD134';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.resolved_at IS NOT NULL OR NEW.sla_breached_at IS NOT NULL OR NEW.assignee IS NOT NULL THEN
            RAISE EXCEPTION 'a compliance flag is raised open, unassigned and within SLA' USING ERRCODE = 'GD134';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.disclosure_id IS NOT NULL AND ga_destroy_branch('disclosure:' || OLD.disclosure_id) THEN
        IF NOT ga_nulls_only(to_jsonb(OLD), to_jsonb(NEW), ARRAY['policy_no']) THEN
            RAISE EXCEPTION 'compliance flag destruction nulls only policy_no' USING ERRCODE = 'GD113';
        END IF;
        RETURN NEW;
    END IF;
    IF (SELECT bool_or((to_jsonb(NEW) -> c) IS DISTINCT FROM (to_jsonb(OLD) -> c)) FROM unnest(fixed) c) THEN
        RAISE EXCEPTION 'compliance flag % identity, type, target, raise time and rule copies are fixed', OLD.flag_id USING ERRCODE = 'GD134';
    END IF;
    IF OLD.resolved_at IS NOT NULL THEN
        RAISE EXCEPTION 'compliance flag % is resolved and never changes (a recurrence is a new flag)', OLD.flag_id USING ERRCODE = 'GD134';
    END IF;
    IF OLD.sla_breached_at IS NOT NULL AND NEW.sla_breached_at IS DISTINCT FROM OLD.sla_breached_at THEN
        RAISE EXCEPTION 'compliance flag % SLA breach is marked once', OLD.flag_id USING ERRCODE = 'GD134';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_compliance_flag_guard
    BEFORE INSERT OR UPDATE OR DELETE ON compliance_flag
    FOR EACH ROW EXECUTE FUNCTION ga_compliance_flag_guard();
CREATE TRIGGER trg_compliance_flag_no_truncate
    BEFORE TRUNCATE ON compliance_flag
    FOR EACH STATEMENT EXECUTE FUNCTION ga_compliance_flag_guard();

-- ---------------------------------------------------------------------------------------------
-- 5. collection_rate_snapshot(6B 계획 §2.4·§5, 승인 §3) — 내부 지표(규제 정의 없음, §14 #17), 산식 ID와 함께 append-only.
--   유일 키에 룰 버전 — 같은 (달, 조직, 룰 버전) 재계산은 거부, 정정은 새 룰 버전. rate_bp는 정수 나눗셈(버림), 분모 0이면 NULL.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE collection_rate_snapshot (
    tenant_id       TEXT        NOT NULL REFERENCES tenant (tenant_id),
    snapshot_id     UUID        NOT NULL,
    period_month    DATE        NOT NULL,
    org_path        TEXT        NOT NULL,
    formula         TEXT        NOT NULL,
    denominator     INTEGER     NOT NULL,
    numerator       INTEGER     NOT NULL,
    rate_bp         INTEGER,
    computed_at     TIMESTAMPTZ NOT NULL,
    rule_version_id TEXT        NOT NULL,
    inputs_hash     TEXT        NOT NULL,
    job_id          UUID        NOT NULL,
    PRIMARY KEY (tenant_id, snapshot_id),
    CONSTRAINT ux_collection_rate_snapshot UNIQUE (tenant_id, period_month, org_path, rule_version_id),
    CONSTRAINT ck_collection_rate_month CHECK (extract(day FROM period_month) = 1),
    CONSTRAINT ck_collection_rate_org CHECK (org_path = '/' OR org_path ~ '^(/[A-Za-z0-9_-]+)+$'),
    CONSTRAINT ck_collection_rate_formula CHECK (formula ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_collection_rate_counts CHECK (denominator >= 0 AND numerator >= 0 AND numerator <= denominator),
    CONSTRAINT ck_collection_rate_bp CHECK ((denominator = 0 AND rate_bp IS NULL)
                                            OR (denominator > 0 AND rate_bp = (numerator::bigint * 10000) / denominator)),
    CONSTRAINT ck_collection_rate_hash CHECK (inputs_hash ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION ga_collection_rate_snapshot_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'collection_rate_snapshot is append-only (%); recompute under a new rule version', TG_OP USING ERRCODE = 'GD135';
END
$$;
CREATE TRIGGER trg_collection_rate_snapshot_guard
    BEFORE UPDATE OR DELETE ON collection_rate_snapshot
    FOR EACH ROW EXECUTE FUNCTION ga_collection_rate_snapshot_guard();
CREATE TRIGGER trg_collection_rate_snapshot_no_truncate
    BEFORE TRUNCATE ON collection_rate_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION ga_collection_rate_snapshot_guard();

-- ---------------------------------------------------------------------------------------------
-- 6. 작업 종류·아웃박스 이벤트(추가형)
-- ---------------------------------------------------------------------------------------------
ALTER TABLE async_job DROP CONSTRAINT ck_job_kind;
ALTER TABLE async_job ADD CONSTRAINT ck_job_kind CHECK (kind IN ('ANCHOR', 'EXPIRE', 'RECONCILE', 'DESTROY', 'DESTROY_DRY_RUN', 'VERIFY_TENANT',
                                                                 'NOTIFY', 'IDEMPOTENCY_PURGE', 'FLAG_SLA_SWEEP', 'COLLECTION_RATE_SNAPSHOT',
                                                                 'ABANDON_DRAFTS', 'RETENTION_RECOMPUTE', 'CONTRACT_LINK_IMPORT',
                                                                 'CONTRACT_LINK_UNMATCHED_PURGE'));
ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_event_type;
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_event_type
    CHECK (type IN ('DisclosureCreated', 'DisclosureSealed', 'SignatureCaptured', 'DisclosureCompleted', 'DisclosureVoided', 'DisclosureSuperseded',
                    'PolicyLinked', 'ComplianceFlagRaised', 'DisclosureDestroyed', 'DisclosureAbandoned'));

-- ---------------------------------------------------------------------------------------------
-- 7. 함수
-- ---------------------------------------------------------------------------------------------
-- 초안 폐기(6B 계획 §6): 봉인 전 초안을 ABANDONED 묘비로 — 자유 텍스트(추천사유·예외 승인 사유·항목 값)와 청약번호를 지운다. 행 삭제 없음.
-- 지운 값의 해시는 앱이 호출 전에 읽어 감사에 남긴다(파기와 같은 규약). 체인·채번과 무관(번호 없음).
CREATE FUNCTION ga_draft_abandon(p_tenant TEXT, p_disclosure UUID, p_at TIMESTAMPTZ, p_by TEXT) RETURNS VOID
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    st TEXT;
    gone TIMESTAMPTZ;
BEGIN
    IF p_tenant IS NULL OR p_disclosure IS NULL OR p_at IS NULL OR p_by IS NULL OR btrim(p_by) = '' THEN
        RAISE EXCEPTION 'tenant, disclosure, time and actor are required' USING ERRCODE = '22004';
    END IF;
    IF p_tenant IS DISTINCT FROM current_setting('app.tenant_id', true) THEN
        RAISE EXCEPTION 'abandonment runs in the bound tenant only' USING ERRCODE = 'GD133';
    END IF;
    SELECT d.status, d.destroyed_at INTO st, gone
      FROM disclosure d
     WHERE d.tenant_id = p_tenant AND d.disclosure_id = p_disclosure
       FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'disclosure % not found', p_disclosure USING ERRCODE = 'GD133';
    END IF;
    IF NOT ga_is_mutable_status(st) OR gone IS NOT NULL THEN
        RAISE EXCEPTION 'disclosure % is % — only a draft before sealing is abandoned', p_disclosure, st USING ERRCODE = 'GD133';
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

-- 파기 함수 교체는 소유자(정의자 롤)로 한다(V11과 같다). 권한(EXECUTE는 파기자 롤만)은 CREATE OR REPLACE가 유지한다.
GRANT CREATE ON SCHEMA public TO disclosure_destroy_definer;
SET ROLE disclosure_destroy_definer;

-- 파기(V11 판 + 6B): 확인서의 청약번호, 계약 연결 이력의 증권·청약 번호를 함께 지운다
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
    UPDATE contract_link SET policy_no = NULL, application_no = NULL
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure AND (policy_no IS NOT NULL OR application_no IS NOT NULL);
    UPDATE disclosure
       SET void_reason_text = NULL, supersede_reason_text = NULL, policy_no = NULL, application_no = NULL, destroyed_at = p_at, destroyed_by = p_by
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure;
    PERFORM set_config('ga.destroy', '', true);
END
$$;

-- 고객 파기(V11 판 + 6B): 폐기된 초안(ABANDONED 묘비)은 "살아 있는 확인서"가 아니다
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
                WHERE x.tenant_id = p_tenant AND x.customer_ref = p_customer_ref AND x.destroyed_at IS NULL AND x.status <> 'ABANDONED') THEN
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

-- ---------------------------------------------------------------------------------------------
-- 8. RLS(V2와 같은 정책)·권한
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['contract_link', 'contract_link_unmatched', 'collection_rate_snapshot']
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

GRANT SELECT, INSERT, UPDATE ON TABLE contract_link TO disclosure_app;
GRANT SELECT, INSERT, DELETE ON TABLE contract_link_unmatched TO disclosure_app;
GRANT SELECT, INSERT ON TABLE collection_rate_snapshot TO disclosure_app;

-- 정의자 롤: 폐기·파기가 바꾸는 컬럼만(V9와 같은 방식 — 테이블 소유자가 아니고 RLS를 따른다)
GRANT SELECT ON TABLE contract_link, disclosure_item TO disclosure_destroy_definer;
GRANT UPDATE (policy_no, application_no) ON TABLE contract_link TO disclosure_destroy_definer;
GRANT UPDATE (status, abandoned_at, application_no) ON TABLE disclosure TO disclosure_destroy_definer;
GRANT UPDATE (field_values) ON TABLE disclosure_item TO disclosure_destroy_definer;
GRANT EXECUTE ON FUNCTION ga_require_mutable_parent(TEXT, UUID, TEXT) TO disclosure_destroy_definer;

REVOKE ALL ON FUNCTION ga_draft_abandon(TEXT, UUID, TIMESTAMPTZ, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ga_draft_abandon(TEXT, UUID, TIMESTAMPTZ, TEXT) TO disclosure_abandoner;
GRANT USAGE ON SCHEMA public TO disclosure_abandoner;
GRANT CREATE ON SCHEMA public TO disclosure_destroy_definer;
ALTER FUNCTION ga_draft_abandon(TEXT, UUID, TIMESTAMPTZ, TEXT) OWNER TO disclosure_destroy_definer;
REVOKE CREATE ON SCHEMA public FROM disclosure_destroy_definer;

REVOKE ALL ON FUNCTION ga_contract_link_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_contract_link_unmatched_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_compliance_flag_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_collection_rate_snapshot_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_disclosure_guard_insert_abandoned() FROM PUBLIC;
