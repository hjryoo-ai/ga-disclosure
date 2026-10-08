-- =============================================================================================
-- V12__api_jobs_notify.sql — Phase 6A: 인가·REST·작업·통지의 저장 기반, 5 수용심사 R1·4-eyes
--
-- (6A 계획 §2, 승인 2026-10-03 Q1·Q2·Q3·Q8·Q11·Q12·Q13)
--   anchor              : 앵커 날짜 = 생성 시각의 KST 날짜(R1 — 소급 기입 폐지). 검증형 CHECK(Q13): 기존 소급 행이 있으면 실패한다.
--   legal_hold          : 해제자 ≠ 설정자(4-eyes, 유스케이스와 DB 양쪽).
--   identity_link       : 설계사가 아닌 주체(COMPLIANCE·SCHEDULER·FEED_CONSUMER) — agent_id·org_path NULL 허용, 역할 닫힌 집합,
--                         AGENT ⇒ agent_id, AGENT·MANAGER ⇒ 조직 경로, 서비스 주체는 단독(Q2).
--   disclosure.org_path : 작성 시점 조직 스냅샷(Q1). INSERT 필수·이후 불변(GD124). 기존 행은 NULL(백필 없음).
--   sign_session        : 원격 링크 토큰은 발송 때 생긴다(Q3). token_hash NULL → 값 1회는 sent_at과 같은 문장에서만(GD123).
--   idempotency_key     : Idempotency-Key 청구·완료(영수증 튜플 + 응답 해시, 본문 없음 — Q4). GD120.
--   async_job           : 작업 리소스·상태 전이(QUEUED→RUNNING→{SUCCEEDED,FAILED}, QUEUED→FAILED — Q11). GD121.
--   notification_outbox : 서명 링크 통지 대기열. 전화번호·토큰을 담을 컬럼이 없다. GD122.
--
-- 롤 disclosure_job_lock(작업 잠금 전용, 테이블 권한 0 — 승인 Q8)은 클러스터 수준이라 docker/postgres/init-roles.sql이 만든다.
-- 여기서는 존재·속성·권한 0을 단언한다(advisory lock의 잠금 공간은 롤과 무관하게 데이터베이스 전체다).
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 0. 롤 단언
-- ---------------------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'disclosure_job_lock'
                     AND rolcanlogin AND NOT rolbypassrls AND NOT rolsuper AND NOT rolinherit AND NOT rolcreaterole AND NOT rolcreatedb) THEN
        RAISE EXCEPTION 'V12 needs role disclosure_job_lock (LOGIN, NOINHERIT, NOBYPASSRLS) — see docker/postgres/init-roles.sql';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member WHERE r.rolname = 'disclosure_job_lock') THEN
        RAISE EXCEPTION 'disclosure_job_lock is a member of no role';
    END IF;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 1. R1·4-eyes
-- ---------------------------------------------------------------------------------------------
ALTER TABLE anchor ADD CONSTRAINT ck_anchor_date_is_creation_day
    CHECK (anchor_date = (created_at AT TIME ZONE 'Asia/Seoul')::date);
ALTER TABLE legal_hold ADD CONSTRAINT ck_legal_hold_four_eyes
    CHECK (released_by IS NULL OR released_by <> placed_by);

-- ---------------------------------------------------------------------------------------------
-- 2. identity_link — 설계사가 아닌 주체(Q2). OPERATOR는 CLI 채널에서만 생기며 여기 역할이 아니다.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE identity_link ALTER COLUMN agent_id DROP NOT NULL, ALTER COLUMN org_path DROP NOT NULL;
ALTER TABLE identity_link ADD CONSTRAINT ck_identity_link_roles CHECK (
    cardinality(roles) >= 1
    AND roles <@ ARRAY['AGENT', 'MANAGER', 'COMPLIANCE', 'SCHEDULER', 'FEED_CONSUMER']::text[]);
ALTER TABLE identity_link ADD CONSTRAINT ck_identity_link_agent CHECK (
    NOT ('AGENT' = ANY (roles)) OR agent_id IS NOT NULL);
ALTER TABLE identity_link ADD CONSTRAINT ck_identity_link_org CHECK (
    (org_path IS NULL OR org_path ~ '^(/[A-Za-z0-9_-]+)+$')
    AND (NOT (roles && ARRAY['AGENT', 'MANAGER']::text[]) OR org_path IS NOT NULL));
ALTER TABLE identity_link ADD CONSTRAINT ck_identity_link_service_alone CHECK (
    NOT (roles && ARRAY['SCHEDULER', 'FEED_CONSUMER']::text[])
    OR (cardinality(roles) = 1 AND agent_id IS NULL AND org_path IS NULL));

-- ---------------------------------------------------------------------------------------------
-- 3. disclosure.org_path — 작성 시점 조직 스냅샷(Q1). 정정·재기준의 새 버전은 그 행위자의 현재 경로를 다시 읽는다(앱).
--    MANAGER 범위는 경로 세그먼트 접두(/HQ는 /HQX에 맞지 않는다). NULL(V12 이전 행)은 MANAGER 범위에 들지 않는다.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE disclosure ADD COLUMN org_path TEXT;
ALTER TABLE disclosure ADD CONSTRAINT ck_disclosure_org_path CHECK (org_path IS NULL OR org_path ~ '^(/[A-Za-z0-9_-]+)+$');

CREATE FUNCTION ga_disclosure_org_path_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.org_path IS NULL THEN
        RAISE EXCEPTION 'disclosure % records the author''s organisation path at creation', NEW.disclosure_id
            USING ERRCODE = 'GD124';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.org_path IS DISTINCT FROM OLD.org_path THEN
        RAISE EXCEPTION 'disclosure % organisation path is fixed at creation', OLD.disclosure_id
            USING ERRCODE = 'GD124';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_disclosure_org_path_guard
    BEFORE INSERT OR UPDATE ON disclosure
    FOR EACH ROW EXECUTE FUNCTION ga_disclosure_org_path_guard();

-- ---------------------------------------------------------------------------------------------
-- 4. sign_session — 원격 링크 토큰은 발송 때(Q3). TOUCH_PAD·PAPER_SCAN은 지금처럼 발급 때 정한다.
--    V9 가드를 대체한다: 파기 분기·GD101 규칙 그대로, token_hash만 고정 컬럼에서 빼 별도 규칙(GD123).
--    이미 값이 있는 token_hash의 변경은 여전히 GD101(고정 컬럼)이다.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE sign_session ALTER COLUMN token_hash DROP NOT NULL;
ALTER TABLE sign_session ADD CONSTRAINT ck_sign_session_token_at_send CHECK (
    token_hash IS NOT NULL OR (channel = 'REMOTE_LINK' AND sent_at IS NULL));
ALTER TABLE sign_session ADD CONSTRAINT ck_sign_session_sent_has_token CHECK (
    sent_at IS NULL OR token_hash IS NOT NULL);

CREATE OR REPLACE FUNCTION ga_sign_session_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_status TEXT;
    parent_doc    TEXT;
    parent_pdf    TEXT;
    fixed CONSTANT TEXT[] := ARRAY['tenant_id', 'session_id', 'disclosure_id', 'signer_role', 'channel', 'issued_by',
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
       IS DISTINCT FROM (SELECT jsonb_object_agg(k, to_jsonb(OLD) -> k) FROM unnest(fixed) k)
        OR (OLD.token_hash IS NOT NULL AND NEW.token_hash IS DISTINCT FROM OLD.token_hash) THEN
        RAISE EXCEPTION 'sign_session % identity and pinned columns are immutable', OLD.session_id
            USING ERRCODE = 'GD101';
    END IF;
    IF OLD.token_hash IS NULL AND NEW.token_hash IS NOT NULL
        AND (OLD.status <> 'OPEN' OR NEW.status <> 'OPEN' OR OLD.channel <> 'REMOTE_LINK'
             OR OLD.sent_at IS NOT NULL OR NEW.sent_at IS NULL) THEN
        RAISE EXCEPTION 'sign_session % gets its link token once, when an open remote link is sent', OLD.session_id
            USING ERRCODE = 'GD123';
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

-- ---------------------------------------------------------------------------------------------
-- 5. idempotency_key — 테넌트 × 주체 × 키. 요청 해시 = SHA-256(JCS{method, routeTemplate, pathVariables, body}).
--    완료 = 상태·영수증 튜플(닫힌 스키마, 개인정보 없음)·응답 바이트 해시를 한 번에. 진행 중 행은 임차 인수(claim_seq + 1)만.
--    삭제는 만료된 행만(작업 IDEMPOTENCY_PURGE). GD120.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE idempotency_key (
    tenant_id       TEXT        NOT NULL REFERENCES tenant (tenant_id),
    actor_subject   TEXT        NOT NULL,
    idem_key        TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    claim_seq       SMALLINT    NOT NULL DEFAULT 1,
    claimed_at      TIMESTAMPTZ NOT NULL,
    response_status SMALLINT,
    response_ref    JSONB,
    response_hash   TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, actor_subject, idem_key),
    CONSTRAINT ck_idem_key CHECK (idem_key ~ '^[A-Za-z0-9_-]{16,128}$' AND btrim(actor_subject) <> ''),
    CONSTRAINT ck_idem_hashes CHECK (request_hash ~ '^[0-9a-f]{64}$' AND (response_hash IS NULL OR response_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_idem_claim CHECK (claim_seq >= 1),
    CONSTRAINT ck_idem_status CHECK (response_status IS NULL OR response_status BETWEEN 200 AND 499),
    CONSTRAINT ck_idem_done CHECK ((response_status IS NULL) = (response_hash IS NULL)
                                   AND (response_status IS NULL) = (response_ref IS NULL)),
    CONSTRAINT ck_idem_ref CHECK (response_ref IS NULL OR jsonb_typeof(response_ref) = 'object'),
    CONSTRAINT ck_idem_expiry CHECK (expires_at > created_at AND claimed_at >= created_at)
);
CREATE INDEX ix_idempotency_key_expiry ON idempotency_key (tenant_id, expires_at);

CREATE FUNCTION ga_idempotency_key_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'idempotency_key is purged row by row after expiry' USING ERRCODE = 'GD120';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.expires_at >= now() THEN
            RAISE EXCEPTION 'idempotency key of % is live until %', OLD.actor_subject, OLD.expires_at USING ERRCODE = 'GD120';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.claim_seq <> 1 OR NEW.response_status IS NOT NULL OR NEW.claimed_at <> NEW.created_at THEN
            RAISE EXCEPTION 'idempotency key is first claimed in progress' USING ERRCODE = 'GD120';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE
    IF OLD.response_status IS NOT NULL THEN
        RAISE EXCEPTION 'a completed idempotency key never changes' USING ERRCODE = 'GD120';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['claim_seq', 'claimed_at', 'response_status', 'response_ref', 'response_hash'])
       IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['claim_seq', 'claimed_at', 'response_status', 'response_ref', 'response_hash']) THEN
        RAISE EXCEPTION 'idempotency key identity, request hash and expiry are fixed' USING ERRCODE = 'GD120';
    END IF;
    IF NEW.response_status IS NULL THEN
        -- 임차 인수: 순번 + 1, 청구 시각 전진
        IF NEW.claim_seq <> OLD.claim_seq + 1 OR NEW.claimed_at <= OLD.claimed_at THEN
            RAISE EXCEPTION 'an in-progress idempotency key is only taken over (claim + 1, later claim time)' USING ERRCODE = 'GD120';
        END IF;
    ELSIF NEW.claim_seq <> OLD.claim_seq OR NEW.claimed_at <> OLD.claimed_at THEN
        RAISE EXCEPTION 'completion records the response only' USING ERRCODE = 'GD120';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_idempotency_key_guard
    BEFORE INSERT OR UPDATE OR DELETE ON idempotency_key
    FOR EACH ROW EXECUTE FUNCTION ga_idempotency_key_guard();
CREATE TRIGGER trg_idempotency_key_no_truncate
    BEFORE TRUNCATE ON idempotency_key
    FOR EACH STATEMENT EXECUTE FUNCTION ga_idempotency_key_guard();

-- ---------------------------------------------------------------------------------------------
-- 6. async_job — 작업 리소스. 상태표(설계서 §6 job-states 블록이 정본, JobStateTableTest가 이 가드와 대조):
--      - → QUEUED, QUEUED → RUNNING, RUNNING → SUCCEEDED, RUNNING → FAILED, QUEUED → FAILED(Q11). 종단은 불변(GD121).
--    ANCHOR 행은 CLI만 만든다(승인 Q7 — 앵커는 플랫폼 배치). 보고서는 테넌트 저장소 reports/에 보고서별 DEK(테넌트 KEK로 감쌈)로
--    암호화해 두고, 행에는 위치·평문 해시·감싼 키만 둔다(개인정보 아님). 활성 행 유일 인덱스는 advisory lock의 벨트이며,
--    DESTROY와 DESTROY_DRY_RUN은 같은 키를 쓴다(판정 중 파기가 겹치면 dry-run 보고서가 거짓이 된다).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE async_job (
    tenant_id           TEXT        NOT NULL REFERENCES tenant (tenant_id),
    job_id              UUID        NOT NULL,
    kind                TEXT        NOT NULL,
    status              TEXT        NOT NULL,
    requested_by        TEXT        NOT NULL,
    channel             TEXT        NOT NULL,
    params              JSONB       NOT NULL DEFAULT '{}'::jsonb,
    requested_at        TIMESTAMPTZ NOT NULL,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    result_ref          TEXT,
    report_sha256       TEXT,
    report_key_wrapped  BYTEA,
    report_kek_id       TEXT,
    error_code          TEXT,
    PRIMARY KEY (tenant_id, job_id),
    CONSTRAINT ck_job_kind CHECK (kind IN ('ANCHOR', 'EXPIRE', 'RECONCILE', 'DESTROY', 'DESTROY_DRY_RUN', 'VERIFY_TENANT',
                                           'NOTIFY', 'IDEMPOTENCY_PURGE')),
    CONSTRAINT ck_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_job_channel CHECK (channel IN ('HTTP', 'CLI')),
    CONSTRAINT ck_job_requested_by CHECK (btrim(requested_by) <> ''),
    CONSTRAINT ck_job_params CHECK (jsonb_typeof(params) = 'object'),
    CONSTRAINT ck_job_times CHECK (
        CASE status
            WHEN 'QUEUED' THEN started_at IS NULL AND finished_at IS NULL
            WHEN 'RUNNING' THEN started_at IS NOT NULL AND finished_at IS NULL
            WHEN 'SUCCEEDED' THEN started_at IS NOT NULL AND finished_at IS NOT NULL
            ELSE finished_at IS NOT NULL
        END
        AND (started_at IS NULL OR started_at >= requested_at)
        AND (finished_at IS NULL OR finished_at >= coalesce(started_at, requested_at))),
    CONSTRAINT ck_job_result CHECK (
        (status = 'SUCCEEDED') = (result_ref IS NOT NULL)
        AND (result_ref IS NULL) = (report_sha256 IS NULL)
        AND (result_ref IS NULL) = (report_key_wrapped IS NULL)
        AND (result_ref IS NULL) = (report_kek_id IS NULL)
        AND (status = 'FAILED') = (error_code IS NOT NULL)),
    CONSTRAINT ck_job_formats CHECK (
        (result_ref IS NULL OR result_ref = tenant_id || '/reports/' || job_id)
        AND (report_sha256 IS NULL OR report_sha256 ~ '^[0-9a-f]{64}$')
        AND (report_kek_id IS NULL OR btrim(report_kek_id) <> '')
        AND (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'))
);
CREATE UNIQUE INDEX ux_async_job_active ON async_job (tenant_id, (CASE kind WHEN 'DESTROY_DRY_RUN' THEN 'DESTROY' ELSE kind END))
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ix_async_job_recent ON async_job (tenant_id, requested_at DESC, job_id);

CREATE FUNCTION ga_async_job_guard() RETURNS trigger
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
CREATE TRIGGER trg_async_job_guard
    BEFORE INSERT OR UPDATE OR DELETE ON async_job
    FOR EACH ROW EXECUTE FUNCTION ga_async_job_guard();
CREATE TRIGGER trg_async_job_no_truncate
    BEFORE TRUNCATE ON async_job
    FOR EACH STATEMENT EXECUTE FUNCTION ga_async_job_guard();

-- ---------------------------------------------------------------------------------------------
-- 7. notification_outbox — 서명 링크 통지. 수신자는 가명(customer_ref), 대상은 세션 ID. 전화번호·토큰 원문을 담을 컬럼이 없다.
--    세션 발급 트랜잭션에서 적재하고, 디스패처가 행마다 한 트랜잭션으로 발송한다. GD122.
--    attempts = 실패한 시도 수. PENDING 안에서는 실패 기록(+1·다음 시각 전진·오류 코드)만, 끝 상태로는 1회.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE notification_outbox (
    tenant_id        TEXT        NOT NULL REFERENCES tenant (tenant_id),
    notification_id  UUID        NOT NULL,
    kind             TEXT        NOT NULL,
    customer_ref     TEXT        NOT NULL,
    session_id       UUID        NOT NULL,
    status           TEXT        NOT NULL,
    attempts         SMALLINT    NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL,
    last_error_code  TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    sent_at          TIMESTAMPTZ,
    closed_at        TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, notification_id),
    FOREIGN KEY (tenant_id, customer_ref) REFERENCES customer_ref (tenant_id, customer_ref),
    FOREIGN KEY (tenant_id, session_id) REFERENCES sign_session (tenant_id, session_id),
    CONSTRAINT ck_notify_kind CHECK (kind IN ('SIGN_LINK')),
    CONSTRAINT ck_notify_status CHECK (status IN ('PENDING', 'SENT', 'DEAD', 'CANCELLED')),
    CONSTRAINT ck_notify_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_notify_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT ck_notify_closed CHECK ((status = 'PENDING') = (closed_at IS NULL)),
    CONSTRAINT ck_notify_error CHECK (
        (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
        AND (status NOT IN ('DEAD', 'CANCELLED') OR last_error_code IS NOT NULL))
);
CREATE UNIQUE INDEX ux_notification_session ON notification_outbox (tenant_id, session_id) WHERE kind = 'SIGN_LINK';
CREATE INDEX ix_notification_due ON notification_outbox (tenant_id, next_attempt_at) WHERE status = 'PENDING';

CREATE FUNCTION ga_notification_outbox_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'notification_outbox rows are never removed (%)', TG_OP USING ERRCODE = 'GD122';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NEW.attempts <> 0 OR NEW.last_error_code IS NOT NULL THEN
            RAISE EXCEPTION 'notification % starts PENDING with no attempts', NEW.notification_id USING ERRCODE = 'GD122';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'notification % is % and no longer changes', OLD.notification_id, OLD.status USING ERRCODE = 'GD122';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status', 'attempts', 'next_attempt_at', 'last_error_code', 'sent_at', 'closed_at'])
       IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'attempts', 'next_attempt_at', 'last_error_code', 'sent_at', 'closed_at']) THEN
        RAISE EXCEPTION 'notification % identity, recipient and session are immutable', OLD.notification_id USING ERRCODE = 'GD122';
    END IF;
    IF NEW.status = 'PENDING' THEN
        IF NEW.attempts <> OLD.attempts + 1 OR NEW.next_attempt_at <= OLD.next_attempt_at OR NEW.last_error_code IS NULL THEN
            RAISE EXCEPTION 'a pending notification records one failed attempt at a time' USING ERRCODE = 'GD122';
        END IF;
    ELSIF NEW.status = 'SENT' THEN
        IF NEW.attempts <> OLD.attempts OR NEW.next_attempt_at <> OLD.next_attempt_at
            OR NEW.last_error_code IS DISTINCT FROM OLD.last_error_code THEN
            RAISE EXCEPTION 'sending notification % records the send time only', OLD.notification_id USING ERRCODE = 'GD122';
        END IF;
    ELSIF NEW.attempts NOT IN (OLD.attempts, OLD.attempts + 1) OR NEW.next_attempt_at <> OLD.next_attempt_at THEN
        RAISE EXCEPTION 'closing notification % records at most the last failure', OLD.notification_id USING ERRCODE = 'GD122';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_notification_outbox_guard
    BEFORE INSERT OR UPDATE OR DELETE ON notification_outbox
    FOR EACH ROW EXECUTE FUNCTION ga_notification_outbox_guard();
CREATE TRIGGER trg_notification_outbox_no_truncate
    BEFORE TRUNCATE ON notification_outbox
    FOR EACH STATEMENT EXECUTE FUNCTION ga_notification_outbox_guard();

-- ---------------------------------------------------------------------------------------------
-- 8. RLS(V2와 같은 정책)·권한 — 필요한 동사만
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['idempotency_key', 'async_job', 'notification_outbox']
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

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE idempotency_key TO disclosure_app;
GRANT SELECT, INSERT, UPDATE ON TABLE async_job TO disclosure_app;
GRANT SELECT, INSERT, UPDATE ON TABLE notification_outbox TO disclosure_app;

REVOKE ALL ON FUNCTION ga_disclosure_org_path_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_sign_session_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_idempotency_key_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_async_job_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_notification_outbox_guard() FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- 9. 작업 잠금 롤의 권한 0 단언(테이블·시퀀스·스키마 USAGE·함수 소유 없음). CONNECT만 init-roles.sql이 준다.
-- ---------------------------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm')
                  AND (has_table_privilege('disclosure_job_lock', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')))
        OR has_schema_privilege('disclosure_job_lock', 'public', 'USAGE')
        OR has_schema_privilege('disclosure_job_lock', 'public', 'CREATE') THEN
        RAISE EXCEPTION 'disclosure_job_lock must hold no privilege in schema public';
    END IF;
END
$$;
