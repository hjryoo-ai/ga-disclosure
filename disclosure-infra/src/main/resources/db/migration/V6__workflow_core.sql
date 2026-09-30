-- =============================================================================================
-- V6__workflow_core.sql — 확인서 워크플로 코어: 상태 열거, 엔진 스냅샷 헤더·항목 등급 복사본의 형태, 예외 승인, 고객 등록 멱등 키
-- (Phase 3A, 설계서 §5·§6.1·§6.3)
--
-- disclosure의 새 스냅샷 헤더 컬럼(정책 버전 2종·tie_break·grade_basis·snapshot_generated_at)은 V3 메타 목록 밖이므로
-- 자동으로 본문(봉인 후 불변)이다. 스냅샷 헤더 6개(grade_snapshot_id 포함)는 함께 있거나 함께 없고, 산출 전 상태(DRAFT·COMPARED)
-- 에는 없다 — 항목이 바뀌면 COMPARED로 되돌리며 스냅샷을 버리는 규칙(§6.1)의 DB 쪽 이중화다.
--
-- disclosure_item의 등급 복사본은 세 형태뿐이다(ck_item_*):
--   미산출   grade_status NULL — 등급·순위·비율·동점·사유·출처 전부 NULL
--   OK       엔진 출처(ENGINE), 등급·라벨·서수≥1·순위≥1·비율·동점 전부 존재, 사유 NULL
--   산출불가 등급·라벨·서수·순위·비율·동점 NULL, 사유·출처 존재
-- 임시등록 항목은 상품키가 없고(엔진 도메인 밖, Phase 2 심사 §3-5) 발행번호가 있으며, 산출되면 로컬 산출불가(LOCAL, TEMP_PRODUCT)
-- 뿐이다. 로컬 출처는 임시등록에만 있다. ratio_to_avg는 여전히 CHECK가 하나도 없다(불투명 문자열 — RatioLabelRoundTripIT가
-- ratio_to_avg를 언급하는 CHECK 0건을 강제한다). 존재 여부는 생성 컬럼 ratio_present가 싣고, 형태 CHECK는 그 컬럼만 본다.
--
-- 거부는 SQLSTATE 'GD0xx'(docs/db-error-codes.md):
--   GD030 review UPDATE·DELETE·TRUNCATE(append-only, V3 ga_append_only 재사용)
--   GD080 봉인된(가변 상태가 아닌) 확인서에 예외 승인 INSERT, 또는 확인서 없음
--   GD065 customer_ref.registration_key 변경(INSERT 때만 정한다)
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- disclosure: 상태 열거, 스냅샷 헤더
-- ---------------------------------------------------------------------------------------------
ALTER TABLE disclosure
    ADD COLUMN grading_policy_version_id TEXT,                                 -- 엔진 gradingPolicyVersionId
    ADD COLUMN ranking_policy_version_id TEXT,                                 -- 엔진 rankingPolicyVersionId
    ADD COLUMN tie_break                 TEXT,                                 -- 엔진 tieBreak
    ADD COLUMN grade_basis               JSONB,                                -- 엔진 basis 원문(객체)
    ADD COLUMN snapshot_generated_at     TIMESTAMPTZ,                          -- 엔진 generatedAt(노후 검사는 3B 봉인 조건)
    ADD CONSTRAINT ck_disclosure_status CHECK (status IN ('DRAFT', 'COMPARED', 'GRADED', 'REASONED', 'SEALED',
                                                          'PARTIALLY_SIGNED', 'COMPLETED', 'VOID', 'SUPERSEDED', 'EXPIRED')),
    ADD CONSTRAINT ck_disclosure_tie_break CHECK (tie_break IS NULL OR tie_break IN ('SHARED_RANK', 'STRICT')),
    ADD CONSTRAINT ck_disclosure_snapshot_header CHECK (
        (grade_snapshot_id IS NULL) = (grading_policy_version_id IS NULL)
        AND (grade_snapshot_id IS NULL) = (ranking_policy_version_id IS NULL)
        AND (grade_snapshot_id IS NULL) = (tie_break IS NULL)
        AND (grade_snapshot_id IS NULL) = (grade_basis IS NULL)
        AND (grade_snapshot_id IS NULL) = (snapshot_generated_at IS NULL)),
    ADD CONSTRAINT ck_disclosure_basis_object CHECK (grade_basis IS NULL OR jsonb_typeof(grade_basis) = 'object'),
    ADD CONSTRAINT ck_disclosure_snapshot_state CHECK (grade_snapshot_id IS NULL OR status NOT IN ('DRAFT', 'COMPARED'));

-- ---------------------------------------------------------------------------------------------
-- disclosure_item: 임시등록 정체성, 등급 복사본의 세 형태
-- ---------------------------------------------------------------------------------------------
ALTER TABLE disclosure_item
    ADD COLUMN group_code         TEXT NOT NULL,                               -- 항목의 상품군(카탈로그 소속, 임시등록은 헤더 상품군) — R-SAME-GROUP
    ADD COLUMN tie                BOOLEAN,                                     -- 엔진 tie(OK일 때만)
    ADD COLUMN unavailable_reason TEXT,                                        -- 엔진 reason 원문 또는 로컬 TEMP_PRODUCT
    ADD COLUMN grade_source       TEXT,                                        -- ENGINE | LOCAL
    ADD COLUMN ratio_present      BOOLEAN GENERATED ALWAYS AS (ratio_to_avg IS NOT NULL) STORED,  -- 내용이 아니라 존재만
    ADD CONSTRAINT ck_item_temp_identity CHECK (temp_product = (product_key IS NULL)),
    ADD CONSTRAINT ck_item_temp_quote CHECK (temp_product = (quote_doc_no IS NOT NULL)
                                             AND (quote_doc_no IS NULL OR btrim(quote_doc_no) <> '')),
    ADD CONSTRAINT ck_item_field_values_object CHECK (jsonb_typeof(field_values) = 'object'),
    ADD CONSTRAINT ck_item_grade_status CHECK (grade_status IS NULL OR grade_status IN ('OK', 'UNAVAILABLE')),
    ADD CONSTRAINT ck_item_grade_source CHECK (grade_source IS NULL OR grade_source IN ('ENGINE', 'LOCAL')),
    ADD CONSTRAINT ck_item_ungraded CHECK (grade_status IS NOT NULL OR (
        grade IS NULL AND grade_label IS NULL AND grade_ordinal IS NULL AND rank_in_set IS NULL AND NOT ratio_present
        AND tie IS NULL AND unavailable_reason IS NULL AND grade_source IS NULL)),
    ADD CONSTRAINT ck_item_ok CHECK (grade_status IS DISTINCT FROM 'OK' OR (
        grade IS NOT NULL AND grade_label IS NOT NULL AND grade_ordinal >= 1 AND rank_in_set >= 1 AND ratio_present
        AND tie IS NOT NULL AND unavailable_reason IS NULL AND grade_source = 'ENGINE')),
    ADD CONSTRAINT ck_item_unavailable CHECK (grade_status IS DISTINCT FROM 'UNAVAILABLE' OR (
        grade IS NULL AND grade_label IS NULL AND grade_ordinal IS NULL AND rank_in_set IS NULL AND NOT ratio_present
        AND tie IS NULL AND unavailable_reason IS NOT NULL AND btrim(unavailable_reason) <> '' AND grade_source IS NOT NULL)),
    ADD CONSTRAINT ck_item_temp_grade CHECK (NOT temp_product OR grade_status IS NULL
        OR (grade_status = 'UNAVAILABLE' AND grade_source = 'LOCAL' AND unavailable_reason = 'TEMP_PRODUCT')),
    ADD CONSTRAINT ck_item_local_is_temp CHECK (grade_source IS DISTINCT FROM 'LOCAL' OR temp_product);

-- 같은 확인서 안에서 상품키는 한 번만(임시등록은 키가 없으므로 제외)
CREATE UNIQUE INDEX ux_disclosure_item_product ON disclosure_item (tenant_id, disclosure_id, product_key)
    WHERE product_key IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- review: 관리자 예외 승인(append-only). 승인은 (확인서, 규칙, 실패 대상의 JCS SHA-256)에 귀속된다(3A 계획 Q3) —
-- 대상이 바뀌면 승인은 자동으로 효력이 없고, 같은 대상이면 재산출 뒤에도 유지된다. 3B 봉인 조건이 소비한다. 인가는 Phase 6.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE review (
    tenant_id     TEXT NOT NULL,
    review_id     UUID NOT NULL,
    disclosure_id UUID NOT NULL,
    rule_id       TEXT NOT NULL,
    subject_hash  TEXT NOT NULL,
    approved_by   TEXT NOT NULL,
    approved_role TEXT NOT NULL,
    approved_at   TIMESTAMPTZ NOT NULL,
    reason        TEXT NOT NULL,
    PRIMARY KEY (tenant_id, review_id),
    FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure (tenant_id, disclosure_id),
    CONSTRAINT ck_review_subject_hash CHECK (subject_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_review_rule_id CHECK (rule_id ~ '^[A-Z][A-Z0-9-]{0,63}$'),
    CONSTRAINT ck_review_reason CHECK (btrim(reason) <> '')
);
CREATE INDEX ix_review_target ON review (tenant_id, disclosure_id, rule_id);

CREATE FUNCTION ga_review_guard_insert() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_status TEXT;
BEGIN
    SELECT d.status INTO parent_status
      FROM disclosure d
     WHERE d.tenant_id = NEW.tenant_id AND d.disclosure_id = NEW.disclosure_id
       FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'review refers to missing disclosure %', NEW.disclosure_id
            USING ERRCODE = 'GD080';
    END IF;
    IF NOT ga_is_mutable_status(parent_status) THEN
        RAISE EXCEPTION 'disclosure % is % : exception approvals are recorded only before sealing', NEW.disclosure_id, parent_status
            USING ERRCODE = 'GD080';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_review_guard_insert
    BEFORE INSERT ON review
    FOR EACH ROW EXECUTE FUNCTION ga_review_guard_insert();
CREATE TRIGGER trg_review_append_only
    BEFORE UPDATE OR DELETE ON review
    FOR EACH ROW EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_review_no_truncate
    BEFORE TRUNCATE ON review
    FOR EACH STATEMENT EXECUTE FUNCTION ga_append_only();

ALTER TABLE review ENABLE ROW LEVEL SECURITY;
ALTER TABLE review FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON review
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
REVOKE ALL ON TABLE review FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE review TO disclosure_app;

REVOKE ALL ON FUNCTION ga_review_guard_insert() FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- customer_ref.registration_key: 등록 멱등 키(3A 계획 Q7). 개인정보가 아닌 불투명 문자열, 테넌트 안에서 유일, INSERT 때만 정한다.
-- 데모는 '{출처}:{파일}#{행 ID}'를 쓰고, 운영 API(Phase 6)에서는 클라이언트가 보내는 Idempotency-Key다.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE customer_ref
    ADD COLUMN registration_key TEXT,
    ADD CONSTRAINT ck_customer_ref_registration_key CHECK (registration_key IS NULL OR registration_key ~ '^[A-Za-z0-9._:@#/-]{1,128}$');
CREATE UNIQUE INDEX ux_customer_ref_registration ON customer_ref (tenant_id, registration_key) WHERE registration_key IS NOT NULL;

CREATE FUNCTION ga_customer_ref_registration_key_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.registration_key IS DISTINCT FROM OLD.registration_key THEN
        RAISE EXCEPTION 'customer_ref % registration_key is set only at registration', OLD.customer_ref
            USING ERRCODE = 'GD065';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_customer_ref_registration_key_guard
    BEFORE UPDATE ON customer_ref
    FOR EACH ROW EXECUTE FUNCTION ga_customer_ref_registration_key_guard();

REVOKE ALL ON FUNCTION ga_customer_ref_registration_key_guard() FROM PUBLIC;
