-- =============================================================================================
-- V1__init.sql — ga-disclosure 스키마 (설계서 v1.3 §5)
-- 정본은 이 파일이다. 기존 V* 파일은 수정하지 않는다(CLAUDE.md 코드 규약).
-- 모든 테넌트 테이블은 tenant_id를 첫 컬럼으로 갖고 PK·인덱스에 포함한다.
-- 실행 롤: disclosure_migrator(소유자). 애플리케이션 롤 disclosure_app 권한은 V2.
-- =============================================================================================

-- rule_version 배타 제약(tenant_id·scope의 = 비교를 GiST에서 쓰기 위해 필요). trusted 확장이라 DB 소유자가 만들 수 있다.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------------------------
-- 테넌트·신원 (포털과 같은 형태, 별도 DB에 독립 보유)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenant (
    tenant_id        TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    engine_base_url  TEXT NOT NULL,                                        -- 테넌트별 엔진 인스턴스
    status           TEXT NOT NULL,                                        -- ACTIVE/PAUSED
    large_ga         BOOLEAN NOT NULL,                                     -- 설계사 500인 이상 여부(대상 판정 입력)
    issuer_mode      TEXT NOT NULL DEFAULT 'SELF',                         -- SELF/ASSOC (D-2)
    gate_mode        TEXT NOT NULL DEFAULT 'WARN',                         -- BLOCK/WARN/OFF (D-8)
    params           JSONB NOT NULL DEFAULT '{"gateRequiresManager": true}'::jsonb  -- 사규 파라미터(D-9 기본값 명시)
);

CREATE TABLE identity_link (                                              -- IdP subject ↔ 설계사/관리자
    tenant_id  TEXT NOT NULL,
    subject    TEXT NOT NULL,
    agent_id   TEXT NOT NULL,                                              -- agent_id는 여기서만 결정된다
    roles      TEXT[] NOT NULL,
    org_path   TEXT NOT NULL,
    PRIMARY KEY (tenant_id, subject)
);

-- ---------------------------------------------------------------------------------------------
-- 룰·서식 (룰 = 데이터)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE rule_version (
    tenant_id        TEXT NOT NULL,
    rule_version_id  TEXT NOT NULL,                                        -- 예: DISC-2026-07
    scope            TEXT NOT NULL,                                        -- GLOBAL(규제) / TENANT(사규)
    apply_from       DATE NOT NULL,
    apply_to         DATE,                                                 -- NULL = 무기한, 구간은 [apply_from, apply_to)
    status           TEXT NOT NULL,                                        -- DRAFT/APPROVED/ACTIVE/RETIRED
    approved_by      TEXT,
    approved_at      TIMESTAMPTZ,
    body             JSONB NOT NULL,                                       -- 부록 D 구조(contracts/rules/v1/rule-version.schema.json)
    PRIMARY KEY (tenant_id, rule_version_id),
    -- 같은 scope에서 기간이 겹치는 ACTIVE는 개시일이 같든 다르든 DB가 거부한다(해석기의 Ambiguous fail-fast와 이중).
    CONSTRAINT ex_rule_version_active_overlap
        EXCLUDE USING gist (tenant_id WITH =, scope WITH =, daterange(apply_from, apply_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE TABLE form_template (
    tenant_id      TEXT NOT NULL,
    template_id    TEXT NOT NULL,
    template_type  TEXT NOT NULL,                                          -- STANDARD / AUTO(예약)
    version        INT NOT NULL,
    apply_from     DATE NOT NULL,
    apply_to       DATE,
    fields         JSONB NOT NULL,                                         -- [{code, label, required, source, order, render}]
    layout         JSONB NOT NULL,                                         -- 렌더러가 읽는 섹션·열 배치
    PRIMARY KEY (tenant_id, template_id, version)
);

-- ---------------------------------------------------------------------------------------------
-- 카탈로그 (외부 정본의 캐시)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE product_group (
    tenant_id   TEXT NOT NULL,
    group_code  TEXT NOT NULL,
    name        TEXT NOT NULL,
    line        TEXT NOT NULL,                                             -- LIFE/NONLIFE
    apply_from  DATE NOT NULL,
    apply_to    DATE,
    source      TEXT NOT NULL,
    PRIMARY KEY (tenant_id, group_code)
);

CREATE TABLE product_catalog (
    tenant_id     TEXT NOT NULL,
    product_key   TEXT NOT NULL,
    insurer_code  TEXT NOT NULL,
    group_code    TEXT NOT NULL,
    product_name  TEXT NOT NULL,
    sale_from     DATE,
    sale_to       DATE,
    defaults      JSONB NOT NULL,                                          -- 서식 항목 기본값(보험료 예시·해약환급예시 등)
    source        TEXT NOT NULL,
    synced_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, product_key)
);

CREATE TABLE insurer_panel (                                              -- "추천가능 보험사" = GA가 위탁계약을 맺은 보험사
    tenant_id     TEXT NOT NULL,
    insurer_code  TEXT NOT NULL,
    insurer_name  TEXT NOT NULL,
    line          TEXT NOT NULL,
    active_from   DATE NOT NULL,
    active_to     DATE,
    PRIMARY KEY (tenant_id, insurer_code, active_from)
);

-- ---------------------------------------------------------------------------------------------
-- 고객 참조 (최소 PII, CRM 도입 전 임시 소유) — *_enc 는 컬럼 암호화(Phase 2)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE customer_ref (
    tenant_id        TEXT NOT NULL,
    customer_ref     TEXT NOT NULL,
    name_enc         BYTEA NOT NULL,
    phone_enc        BYTEA,
    birth_year       SMALLINT,
    crm_customer_id  TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, customer_ref)
);

-- ---------------------------------------------------------------------------------------------
-- 확인서 헤더
-- 본문 컬럼(봉인 후 불변)과 메타 컬럼(봉인 후에도 변경 가능)의 구분은 V3 트리거가 정의한다.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE disclosure (
    tenant_id          TEXT NOT NULL,
    disclosure_id      UUID NOT NULL,
    disclosure_no      TEXT,                                               -- 봉인 시 채번: {tenant}-{yyyy}-{seq}, 봉인 전 NULL
    version            INT NOT NULL DEFAULT 1,
    supersedes_id      UUID,
    superseded_by_id   UUID,                                               -- 메타, write-once(V3)
    agent_id           TEXT NOT NULL,
    customer_ref       TEXT NOT NULL,
    group_code         TEXT NOT NULL,
    template_id        TEXT NOT NULL,
    template_version   INT NOT NULL,
    rule_version_id    TEXT,                                               -- 봉인 시 확정
    issuer_mode        TEXT NOT NULL,                                      -- SELF/ASSOC
    status             TEXT NOT NULL,                                      -- §6.1
    consult_date       DATE NOT NULL,
    grade_snapshot_id  TEXT,                                               -- 엔진 snapshotId
    sealed_at          TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    voided_at          TIMESTAMPTZ,
    void_reason        TEXT,
    policy_no          TEXT,                                               -- 메타데이터(본문 아님)
    contract_date      DATE,                                               -- 메타데이터(본문 아님)
    canonical_hash     TEXT,
    pdf_hash           TEXT,
    chain_hash         TEXT,
    chain_seq          BIGINT,
    retention_until    DATE,
    PRIMARY KEY (tenant_id, disclosure_id),
    UNIQUE (tenant_id, disclosure_no)
);
CREATE INDEX ix_disc_agent ON disclosure (tenant_id, agent_id, status);
CREATE INDEX ix_disc_customer ON disclosure (tenant_id, customer_ref);

-- ---------------------------------------------------------------------------------------------
-- 비교 항목(열): 상품 1개 = 1행
-- ---------------------------------------------------------------------------------------------
CREATE TABLE disclosure_item (
    tenant_id              TEXT NOT NULL,
    disclosure_id          UUID NOT NULL,
    item_no                SMALLINT NOT NULL,
    product_key            TEXT,
    insurer_code           TEXT NOT NULL,
    product_name           TEXT NOT NULL,
    temp_product           BOOLEAN NOT NULL DEFAULT false,
    quote_doc_no           TEXT,                                           -- 임시등록 시 가입설계서 발행번호 필수(Phase 3 검증)
    is_recommended         BOOLEAN NOT NULL,
    requested_by_customer  BOOLEAN NOT NULL DEFAULT false,
    field_values           JSONB NOT NULL,                                 -- 서식 항목값 {code: value}, source 표시 포함
    grade                  TEXT,                                           -- 스냅샷 복사본(아래 grade_status까지)
    grade_label            TEXT,
    grade_ordinal          SMALLINT,
    rank_in_set            SMALLINT,
    ratio_to_avg           TEXT,                                           -- 엔진 원문 그대로(불투명 문자열). 형식 CHECK 없음
    grade_status           TEXT,
    PRIMARY KEY (tenant_id, disclosure_id, item_no)
);
COMMENT ON COLUMN disclosure_item.ratio_to_avg IS
    '엔진 ratioToAvg 원문(불투명 문자열, 설계서 §4.1). NUMERIC 금지: 정규화로 원문·봉인 해시가 바뀌고(0.840→0.84) ORDER BY로 비율 정렬 경로가 열린다(CLAUDE.md 절대 규칙 1). 형식 CHECK도 두지 않는다 — 엔진 원문 형식을 안다고 가정하는 것 자체가 규칙 위반이다.';

CREATE TABLE recommendation (
    tenant_id      TEXT NOT NULL,
    disclosure_id  UUID NOT NULL,
    item_no        SMALLINT NOT NULL,
    reason_codes   TEXT[] NOT NULL,                                        -- rule_version.body.reasonCodes 중 선택(데이터)
    reason_text    TEXT,                                                   -- requiresText 코드 선택 시 필수(Phase 3 검증)
    PRIMARY KEY (tenant_id, disclosure_id, item_no)
);

-- ---------------------------------------------------------------------------------------------
-- 봉인 산출물 (append-only, V3)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE document_artifact (
    tenant_id      TEXT NOT NULL,
    disclosure_id  UUID NOT NULL,
    kind           TEXT NOT NULL,                                          -- CANONICAL_JSON / PDF / SIGNED_PDF / EVIDENCE_ZIP
    storage_key    TEXT NOT NULL,
    sha256         TEXT NOT NULL,
    bytes          BIGINT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, disclosure_id, kind)
);

-- ---------------------------------------------------------------------------------------------
-- 서명
-- ---------------------------------------------------------------------------------------------
CREATE TABLE sign_session (
    tenant_id      TEXT NOT NULL,
    session_id     UUID NOT NULL,
    disclosure_id  UUID NOT NULL,
    signer_role    TEXT NOT NULL,                                          -- CUSTOMER/AGENT/MANAGER
    channel        TEXT NOT NULL,                                          -- TOUCH_PAD/REMOTE_LINK/PAPER_SCAN/CERTIFIED_ESIGN
    token_hash     TEXT,
    expires_at     TIMESTAMPTZ NOT NULL,
    used_at        TIMESTAMPTZ,
    status         TEXT NOT NULL,
    PRIMARY KEY (tenant_id, session_id)
);

CREATE TABLE signature (                                                  -- append-only, 해시 귀속(V3)
    tenant_id        TEXT NOT NULL,
    signature_id     UUID NOT NULL,
    disclosure_id    UUID NOT NULL,
    signer_role      TEXT NOT NULL,
    signer_subject   TEXT,                                                 -- 설계사·관리자 OIDC subject, 고객은 NULL
    channel          TEXT NOT NULL,
    method           TEXT NOT NULL,                                        -- 예: DRAWN / UPLOADED_SCAN / PROVIDER_CERT / SSO_APPROVAL
    signed_doc_hash  TEXT NOT NULL,                                        -- = disclosure.canonical_hash (귀속)
    identity_check   JSONB,
    evidence_key     TEXT NOT NULL,
    evidence_hash    TEXT NOT NULL,
    device           JSONB,
    ip               INET,
    signed_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, signature_id),
    UNIQUE (tenant_id, disclosure_id, signer_role)
);

-- ---------------------------------------------------------------------------------------------
-- 감사 로그 (append-only, 해시체인 — 체인 계산은 Phase 5)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE audit_log (
    tenant_id      TEXT NOT NULL,
    seq            BIGINT NOT NULL,
    at             TIMESTAMPTZ NOT NULL,
    actor_subject  TEXT,
    actor_role     TEXT,
    action         TEXT NOT NULL,
    target_kind    TEXT,
    target_id      TEXT,
    detail         JSONB,
    prev_hash      TEXT NOT NULL,
    entry_hash     TEXT NOT NULL,                                          -- H(prev_hash || canonical(entry))
    PRIMARY KEY (tenant_id, seq)
);

CREATE TABLE audit_anchor (                                               -- 일 1회 체인 헤드 고정
    tenant_id     TEXT NOT NULL,
    anchored_at   TIMESTAMPTZ NOT NULL,
    head_seq      BIGINT NOT NULL,
    head_hash     TEXT NOT NULL,
    external_ref  TEXT,
    PRIMARY KEY (tenant_id, anchored_at)
);

-- ---------------------------------------------------------------------------------------------
-- 준법
-- ---------------------------------------------------------------------------------------------
CREATE TABLE subject_policy (                                             -- 대상 계약 ↔ 확인서 대사
    tenant_id      TEXT NOT NULL,
    policy_no      TEXT NOT NULL,
    agent_id       TEXT NOT NULL,
    contract_date  DATE NOT NULL,
    product_key    TEXT,
    group_code     TEXT,
    required       BOOLEAN NOT NULL,
    disclosure_id  UUID,
    recon_status   TEXT NOT NULL,                                          -- MATCHED/MISSING/LATE/EXEMPT
    checked_at     TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, policy_no)
);

CREATE TABLE compliance_flag (
    tenant_id      TEXT NOT NULL,
    flag_id        UUID NOT NULL,
    type           TEXT NOT NULL,
    disclosure_id  UUID,
    policy_no      TEXT,
    agent_id       TEXT,
    severity       TEXT NOT NULL,
    raised_at      TIMESTAMPTZ NOT NULL,
    resolved_at    TIMESTAMPTZ,
    resolved_by    TEXT,
    resolution     TEXT,
    PRIMARY KEY (tenant_id, flag_id)
);
