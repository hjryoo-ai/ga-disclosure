-- =============================================================================================
-- V4__rule_bundles.sql — 룰·서식 번들 출처, 룰 데이터 불변 트리거, 사규 버전 박제, 테넌트 디렉터리 롤 (Phase 1)
--
-- 규제(GLOBAL) 룰의 정본은 contracts/rules/bundles/의 번들 파일이다. 배포 명령이 각 테넌트에 같은 rule_version_id로
-- 복제하며 source_bundle_id·bundle_hash(= SHA-256(JCS(body)))를 남긴다. 복제본은 불변이고(apply_to만 NULL→값 1회),
-- 준법 배치가 해시를 재계산해 번들과 대조한다(설계서 §5·§6.2·§6.8).
--
-- 거부는 SQLSTATE 'GD0xx'(docs/db-error-codes.md):
--   GD040 rule_version INSERT 상태(GLOBAL=APPROVED, TENANT=DRAFT만)
--   GD041 rule_version 식별자·scope 변경, 또는 TENANT·DRAFT가 아닌 행의 메타 외 컬럼 변경
--   GD042 rule_version.apply_to 재기록        GD043 status 전이 위반(한 단계 전진만)
--   GD044 approved_by·approved_at을 DRAFT→APPROVED 밖에서 변경
--   GD045 rule_version·form_template DELETE·TRUNCATE
--   GD050 form_template 식별자·출처 변경 또는 번들 출처 행 변경   GD051 테넌트 작성본을 적용 개시일 이후 변경
--   GD052 form_template.apply_to 재기록
-- V3와 같은 방식: 메타(변경 허용) 컬럼을 허용 목록으로 두고 나머지는 행 JSON 차이로 판정한다 — 새 컬럼은 기본이 불변이다.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- rule_version: 번들 출처·해시, 값 제약
-- ---------------------------------------------------------------------------------------------
ALTER TABLE rule_version
    ADD COLUMN source_bundle_id TEXT,                                      -- 예: DISC-2026-07@3f2a9c0b1d4e
    ADD COLUMN bundle_hash      TEXT;                                      -- SHA-256(JCS(body)), 소문자 hex 64자

ALTER TABLE rule_version
    ADD CONSTRAINT ck_rule_version_status CHECK (status IN ('DRAFT', 'APPROVED', 'ACTIVE', 'RETIRED')),
    ADD CONSTRAINT ck_rule_version_scope  CHECK (scope IN ('GLOBAL', 'TENANT')),
    -- GLOBAL(규제)은 번들 복제본뿐이고 TENANT(사규)는 번들 출처를 가질 수 없다.
    ADD CONSTRAINT ck_rule_version_bundle CHECK (
        (scope = 'GLOBAL' AND source_bundle_id IS NOT NULL AND bundle_hash IS NOT NULL)
        OR (scope = 'TENANT' AND source_bundle_id IS NULL AND bundle_hash IS NULL)),
    ADD CONSTRAINT ck_rule_version_hash   CHECK (bundle_hash IS NULL OR bundle_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_rule_version_range  CHECK (apply_to IS NULL OR apply_to > apply_from),
    -- 승인 기록은 DRAFT가 아닌 행에만, 그리고 반드시 있다.
    ADD CONSTRAINT ck_rule_version_approval CHECK (
        (status = 'DRAFT' AND approved_by IS NULL AND approved_at IS NULL)
        OR (status <> 'DRAFT' AND approved_by IS NOT NULL AND approved_at IS NOT NULL));

-- 해석 대상은 기준일에 "시행 중이었던" 룰(ACTIVE 또는 RETIRED)이다. 겹침 금지를 RETIRED까지 넓혀, 새 룰의 활성화가
-- 과거 구간과 겹치면 거부한다 — 과거 상담일의 해석이 사후에 바뀌지 않는다(설계서 §5, Phase 1 계획 D5).
ALTER TABLE rule_version DROP CONSTRAINT ex_rule_version_active_overlap;
ALTER TABLE rule_version ADD CONSTRAINT ex_rule_version_in_force_overlap
    EXCLUDE USING gist (tenant_id WITH =, scope WITH =, daterange(apply_from, apply_to, '[)') WITH &&)
    WHERE (status IN ('ACTIVE', 'RETIRED'));

-- ---------------------------------------------------------------------------------------------
-- form_template: 번들 출처·해시, 정본 확인 전 자리표시, 값 제약
-- ---------------------------------------------------------------------------------------------
ALTER TABLE form_template
    ADD COLUMN source_bundle_id     TEXT,                                  -- NULL이면 테넌트 작성본
    ADD COLUMN bundle_hash          TEXT,                                  -- SHA-256(JCS({fields, layout, pendingConfirmation}))
    ADD COLUMN pending_confirmation JSONB NOT NULL DEFAULT '[]';           -- [{ref: TODO(confirm#N), note}] — 필드가 아니다

ALTER TABLE form_template
    ADD CONSTRAINT ck_form_template_type   CHECK (template_type IN ('STANDARD', 'AUTO')),
    ADD CONSTRAINT ck_form_template_bundle CHECK ((source_bundle_id IS NULL) = (bundle_hash IS NULL)),
    ADD CONSTRAINT ck_form_template_hash   CHECK (bundle_hash IS NULL OR bundle_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_form_template_range  CHECK (apply_to IS NULL OR apply_to > apply_from),
    ADD CONSTRAINT ck_form_template_pending CHECK (jsonb_typeof(pending_confirmation) = 'array'),
    -- 같은 서식 유형의 적용 구간은 겹칠 수 없다(해석기의 Ambiguous fail-fast와 이중).
    ADD CONSTRAINT ex_form_template_overlap
        EXCLUDE USING gist (tenant_id WITH =, template_type WITH =, daterange(apply_from, apply_to, '[)') WITH &&);

-- ---------------------------------------------------------------------------------------------
-- disclosure: 적용된 사규(TENANT) 룰 버전. V3 메타 목록에 없으므로 추가 즉시 봉인 후 불변(본문) 컬럼이다.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE disclosure ADD COLUMN tenant_rule_version_id TEXT;             -- 봉인 시 확정, 사규가 없으면 NULL

-- ---------------------------------------------------------------------------------------------
-- 트리거
-- ---------------------------------------------------------------------------------------------

-- 업무 날짜 기준 "오늘"(Asia/Seoul). 테넌트 작성 서식의 수정 가능 기간 판정에만 쓴다.
CREATE FUNCTION ga_today() RETURNS DATE
    LANGUAGE sql STABLE
AS $$ SELECT (now() AT TIME ZONE 'Asia/Seoul')::date $$;

CREATE FUNCTION ga_rule_version_guard_insert() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.scope = 'GLOBAL' AND NEW.status IS DISTINCT FROM 'APPROVED' THEN
        RAISE EXCEPTION 'GLOBAL rule % is a bundle replica and must be inserted as APPROVED (got %)', NEW.rule_version_id, NEW.status
            USING ERRCODE = 'GD040';
    END IF;
    IF NEW.scope = 'TENANT' AND NEW.status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'TENANT rule % must be inserted as DRAFT and approved afterwards (got %)', NEW.rule_version_id, NEW.status
            USING ERRCODE = 'GD040';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION ga_rule_version_guard_update() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    -- 어떤 상태에서든 규칙에 따라 바뀔 수 있는 컬럼. 나머지(body, apply_from, source_bundle_id, bundle_hash, 이후 추가 컬럼)는
    -- scope='TENANT' AND status='DRAFT'일 때만 바뀔 수 있다.
    meta CONSTANT TEXT[] := ARRAY['status', 'apply_to', 'approved_by', 'approved_at'];
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.rule_version_id IS DISTINCT FROM OLD.rule_version_id
       OR NEW.scope IS DISTINCT FROM OLD.scope THEN
        RAISE EXCEPTION 'rule_version % identity and scope are immutable', OLD.rule_version_id
            USING ERRCODE = 'GD041';
    END IF;
    IF NOT (OLD.scope = 'TENANT' AND OLD.status = 'DRAFT')
       AND (to_jsonb(NEW) - meta) IS DISTINCT FROM (to_jsonb(OLD) - meta) THEN
        RAISE EXCEPTION 'rule_version % (% %) body is immutable; issue a new rule_version_id', OLD.rule_version_id, OLD.scope, OLD.status
            USING ERRCODE = 'GD041';
    END IF;
    IF OLD.apply_to IS NOT NULL AND NEW.apply_to IS DISTINCT FROM OLD.apply_to THEN
        RAISE EXCEPTION 'rule_version % apply_to is write-once (% -> %)', OLD.rule_version_id, OLD.apply_to, NEW.apply_to
            USING ERRCODE = 'GD042';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
       AND (OLD.status, NEW.status) NOT IN (('DRAFT', 'APPROVED'), ('APPROVED', 'ACTIVE'), ('ACTIVE', 'RETIRED')) THEN
        RAISE EXCEPTION 'rule_version % status % -> % is not a single forward step', OLD.rule_version_id, OLD.status, NEW.status
            USING ERRCODE = 'GD043';
    END IF;
    IF (NEW.approved_by IS DISTINCT FROM OLD.approved_by OR NEW.approved_at IS DISTINCT FROM OLD.approved_at)
       AND NOT (OLD.status = 'DRAFT' AND NEW.status = 'APPROVED') THEN
        RAISE EXCEPTION 'rule_version % approval is recorded only on DRAFT -> APPROVED', OLD.rule_version_id
            USING ERRCODE = 'GD044';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION ga_rule_data_no_delete() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% rows are audit evidence and are never removed (%)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'GD045';
END
$$;

CREATE FUNCTION ga_form_template_guard_update() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    meta CONSTANT TEXT[] := ARRAY['apply_to'];
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.template_id IS DISTINCT FROM OLD.template_id
       OR NEW.version IS DISTINCT FROM OLD.version
       OR NEW.source_bundle_id IS DISTINCT FROM OLD.source_bundle_id OR NEW.bundle_hash IS DISTINCT FROM OLD.bundle_hash THEN
        RAISE EXCEPTION 'form_template %/% identity and bundle provenance are immutable', OLD.template_id, OLD.version
            USING ERRCODE = 'GD050';
    END IF;
    IF OLD.apply_to IS NOT NULL AND NEW.apply_to IS DISTINCT FROM OLD.apply_to THEN
        RAISE EXCEPTION 'form_template %/% apply_to is write-once (% -> %)', OLD.template_id, OLD.version, OLD.apply_to, NEW.apply_to
            USING ERRCODE = 'GD052';
    END IF;
    IF (to_jsonb(NEW) - meta) IS DISTINCT FROM (to_jsonb(OLD) - meta) THEN
        IF OLD.source_bundle_id IS NOT NULL THEN
            RAISE EXCEPTION 'form_template %/% comes from bundle % and is immutable', OLD.template_id, OLD.version, OLD.source_bundle_id
                USING ERRCODE = 'GD050';
        END IF;
        -- 테넌트 작성본: status가 없으므로 "적용 개시 전"을 미승인으로 본다(Phase 1 지시문 해석).
        IF OLD.apply_from <= ga_today() THEN
            RAISE EXCEPTION 'form_template %/% took effect on % and is immutable', OLD.template_id, OLD.version, OLD.apply_from
                USING ERRCODE = 'GD051';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_rule_version_guard_insert
    BEFORE INSERT ON rule_version
    FOR EACH ROW EXECUTE FUNCTION ga_rule_version_guard_insert();
CREATE TRIGGER trg_rule_version_guard_update
    BEFORE UPDATE ON rule_version
    FOR EACH ROW EXECUTE FUNCTION ga_rule_version_guard_update();
CREATE TRIGGER trg_rule_version_no_delete
    BEFORE DELETE ON rule_version
    FOR EACH ROW EXECUTE FUNCTION ga_rule_data_no_delete();
CREATE TRIGGER trg_rule_version_no_truncate
    BEFORE TRUNCATE ON rule_version
    FOR EACH STATEMENT EXECUTE FUNCTION ga_rule_data_no_delete();

CREATE TRIGGER trg_form_template_guard_update
    BEFORE UPDATE ON form_template
    FOR EACH ROW EXECUTE FUNCTION ga_form_template_guard_update();
CREATE TRIGGER trg_form_template_no_delete
    BEFORE DELETE ON form_template
    FOR EACH ROW EXECUTE FUNCTION ga_rule_data_no_delete();
CREATE TRIGGER trg_form_template_no_truncate
    BEFORE TRUNCATE ON form_template
    FOR EACH STATEMENT EXECUTE FUNCTION ga_rule_data_no_delete();

REVOKE ALL ON FUNCTION ga_today() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_rule_version_guard_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_rule_version_guard_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_rule_data_no_delete() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_form_template_guard_update() FROM PUBLIC;
-- 트리거 함수 안에서 호출하는 보조 함수는 호출 롤의 EXECUTE 권한이 필요하다(트리거 함수는 SECURITY DEFINER가 아니다).
GRANT EXECUTE ON FUNCTION ga_today() TO disclosure_app;

-- ---------------------------------------------------------------------------------------------
-- 테넌트 디렉터리: 운영자 CLI의 `--tenants all` 전용(설계서 §9, Phase 1 계획 D4).
-- disclosure_operator(롤은 init-roles.sql)는 tenant.tenant_id 한 컬럼만 모든 테넌트에 대해 읽을 수 있다. 그 밖의 테이블·컬럼
-- 권한은 없다. 테넌트 데이터 작업은 테넌트마다 disclosure_app으로 바인딩해 수행한다.
-- ---------------------------------------------------------------------------------------------
GRANT USAGE ON SCHEMA public TO disclosure_operator;
GRANT SELECT (tenant_id) ON TABLE tenant TO disclosure_operator;
CREATE POLICY tenant_directory ON tenant AS PERMISSIVE FOR SELECT TO disclosure_operator USING (true);
