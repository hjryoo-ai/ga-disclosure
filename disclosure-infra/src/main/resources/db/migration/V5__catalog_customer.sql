-- =============================================================================================
-- V5__catalog_customer.sql — 카탈로그 출처·유효기간, 수입 이력, 고객 참조 암호화·키 저장소, 준법 플래그 대상 (Phase 2)
--
-- 카탈로그(product_group·product_catalog·insurer_panel)는 외부 정본의 캐시다(설계서 §4.2·§5). 행마다 출처(source),
-- 수입 파일(source_ref = '{파일명}@sha256:{hex}'), 동기 시각(synced_at)을 남기고, 유효기간은 룰과 같은 반개구간 [from, to)다.
-- 파일에서 사라진 행은 삭제하지 않고 기간을 닫는다 — 확인서가 참조한 상품이 사라지면 안 된다(DELETE·TRUNCATE 거부).
--
-- 고객 참조(customer_ref)의 이름·연락처·생년월일은 AES-256-GCM 암호문만 저장한다(설계서 §9, CLAUDE.md 절대 규칙 6).
-- 암호문 형식 = 0x01 ‖ nonce(12) ‖ ciphertext‖tag(16). 행의 세 컬럼은 같은 키(enc_key_id)로 암호화되며, 키는 테넌트별
-- 데이터 키(DEK)를 마스터 키(KEK)로 감싼 형태로만 customer_data_key에 있다. 키 순환은 새 DEK로 재암호화한 뒤 구 키를
-- DESTROYED(감싼 키 NULL)로 만든다 — 그 키를 쓰는 행이 남아 있으면 DB가 거부한다.
--
-- 거부는 SQLSTATE 'GD0xx'(docs/db-error-codes.md):
--   GD060 customer_data_key 식별자·KEK·감싼 키 변경, 상태 역행·건너뛰기   GD061 사용 중인 키 DESTROY
--   GD062 customer_data_key DELETE·TRUNCATE   GD063 customer_ref 식별자·생성 시각 변경
--   GD064 customer_ref DELETE·TRUNCATE(파기는 Phase 5 보존기간 배치가 이 트리거를 대체하는 마이그레이션과 함께 도입한다)
--   GD070 카탈로그 3테이블 DELETE·TRUNCATE      GD071 product_catalog 키 정체성(product_key·insurer_code) 변경
--   catalog_import는 append-only(GD030, V3의 ga_append_only 재사용)
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 키 저장소: 테넌트별 DEK(감싼 형태)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE customer_data_key (
    tenant_id     TEXT NOT NULL,
    key_id        TEXT NOT NULL,                                           -- 예: DEK-{uuid hex}
    kek_id        TEXT NOT NULL,                                           -- DEK를 감싼 마스터 키 ID(KMS 키 ARN·로컬 키 ID)
    wrapped_key   BYTEA,                                                   -- KEK로 감싼 DEK. DESTROYED면 NULL
    status        TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    retired_at    TIMESTAMPTZ,
    destroyed_at  TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, key_id),
    CONSTRAINT ck_customer_data_key_status CHECK (status IN ('ACTIVE', 'RETIRED', 'DESTROYED')),
    CONSTRAINT ck_customer_data_key_wrapped CHECK ((status = 'DESTROYED') = (wrapped_key IS NULL)),
    CONSTRAINT ck_customer_data_key_times CHECK (
        (status = 'ACTIVE' AND retired_at IS NULL AND destroyed_at IS NULL)
        OR (status = 'RETIRED' AND retired_at IS NOT NULL AND destroyed_at IS NULL)
        OR (status = 'DESTROYED' AND retired_at IS NOT NULL AND destroyed_at IS NOT NULL))
);
-- 테넌트당 새 암호화에 쓰는 키는 정확히 하나
CREATE UNIQUE INDEX ux_customer_data_key_active ON customer_data_key (tenant_id) WHERE status = 'ACTIVE';

CREATE FUNCTION ga_customer_data_key_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
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
    -- 감싼 키는 DESTROY 때 NULL이 되는 것 외에는 바뀌지 않는다
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

CREATE FUNCTION ga_customer_data_key_no_delete() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'customer_data_key rows are never removed (%); destroy the key material instead', TG_OP
        USING ERRCODE = 'GD062';
END
$$;

CREATE TRIGGER trg_customer_data_key_guard
    BEFORE UPDATE ON customer_data_key
    FOR EACH ROW EXECUTE FUNCTION ga_customer_data_key_guard();
CREATE TRIGGER trg_customer_data_key_no_delete
    BEFORE DELETE ON customer_data_key
    FOR EACH ROW EXECUTE FUNCTION ga_customer_data_key_no_delete();
CREATE TRIGGER trg_customer_data_key_no_truncate
    BEFORE TRUNCATE ON customer_data_key
    FOR EACH STATEMENT EXECUTE FUNCTION ga_customer_data_key_no_delete();

-- ---------------------------------------------------------------------------------------------
-- 고객 참조: birth_year → birth_date_enc, 키 ID, 암호문 형식
-- ---------------------------------------------------------------------------------------------
ALTER TABLE customer_ref DROP COLUMN birth_year;
ALTER TABLE customer_ref
    ADD COLUMN birth_date_enc BYTEA,                                       -- D-10 원격 본인확인은 생년월일 전체를 대조한다
    ADD COLUMN enc_key_id     TEXT NOT NULL;                               -- 세 암호문 컬럼 공통 키

ALTER TABLE customer_ref
    ADD CONSTRAINT fk_customer_ref_key FOREIGN KEY (tenant_id, enc_key_id) REFERENCES customer_data_key (tenant_id, key_id),
    -- 고객 ID는 개인정보와 무관한 무작위 값이다(이름·연락처의 해시가 아니다 — 가명 조회 경로를 만들지 않는다).
    ADD CONSTRAINT ck_customer_ref_id CHECK (customer_ref ~ '^CR-[0-9a-f]{32}$'),
    -- 암호문 형식 머리(형식 버전 0x01) + 최소 길이(1 + nonce 12 + tag 16). 평문을 잘못 넣으면 여기서 걸린다.
    ADD CONSTRAINT ck_customer_ref_name_enc CHECK (get_byte(name_enc, 0) = 1 AND length(name_enc) >= 29),
    ADD CONSTRAINT ck_customer_ref_phone_enc CHECK (phone_enc IS NULL OR (get_byte(phone_enc, 0) = 1 AND length(phone_enc) >= 29)),
    ADD CONSTRAINT ck_customer_ref_birth_enc CHECK (
        birth_date_enc IS NULL OR (get_byte(birth_date_enc, 0) = 1 AND length(birth_date_enc) >= 29));

CREATE FUNCTION ga_customer_ref_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.customer_ref IS DISTINCT FROM OLD.customer_ref
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'customer_ref % identity and creation time are immutable', OLD.customer_ref
            USING ERRCODE = 'GD063';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_customer_ref_guard
    BEFORE UPDATE ON customer_ref
    FOR EACH ROW EXECUTE FUNCTION ga_customer_ref_guard();

CREATE FUNCTION ga_customer_ref_no_delete() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'customer_ref rows are not removed ad hoc (%); disposal is the retention batch''s job', TG_OP
        USING ERRCODE = 'GD064';
END
$$;

CREATE TRIGGER trg_customer_ref_no_delete
    BEFORE DELETE ON customer_ref
    FOR EACH ROW EXECUTE FUNCTION ga_customer_ref_no_delete();
CREATE TRIGGER trg_customer_ref_no_truncate
    BEFORE TRUNCATE ON customer_ref
    FOR EACH STATEMENT EXECUTE FUNCTION ga_customer_ref_no_delete();

-- ---------------------------------------------------------------------------------------------
-- 카탈로그: 출처·파일 해시·동기 시각 필수, 반개구간 [from, to), 삭제 금지
-- (빈 구간 [x, x)는 "판매 개시 전에 철회된 상품"을 닫는 데 쓴다 — 어느 기준일에도 보이지 않는다)
-- 세 테이블은 V1 이후 쓰는 코드가 없어 비어 있으므로 NOT NULL 추가가 안전하다.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE product_group
    ADD COLUMN source_ref TEXT NOT NULL,
    ADD COLUMN synced_at  TIMESTAMPTZ NOT NULL,
    ADD CONSTRAINT ck_product_group_range CHECK (apply_to IS NULL OR apply_to >= apply_from),
    ADD CONSTRAINT ck_product_group_line CHECK (line IN ('LIFE', 'NONLIFE'));

ALTER TABLE product_catalog
    ALTER COLUMN sale_from SET NOT NULL,
    ADD COLUMN source_ref TEXT NOT NULL,
    ADD CONSTRAINT ck_product_catalog_range CHECK (sale_to IS NULL OR sale_to >= sale_from),
    ADD CONSTRAINT ck_product_catalog_defaults CHECK (jsonb_typeof(defaults) = 'object'),
    ADD CONSTRAINT fk_product_catalog_group FOREIGN KEY (tenant_id, group_code) REFERENCES product_group (tenant_id, group_code);
CREATE INDEX ix_product_catalog_group ON product_catalog (tenant_id, group_code, insurer_code);

ALTER TABLE insurer_panel
    ADD COLUMN source     TEXT NOT NULL,
    ADD COLUMN source_ref TEXT NOT NULL,
    ADD COLUMN synced_at  TIMESTAMPTZ NOT NULL,
    ADD CONSTRAINT ck_insurer_panel_range CHECK (active_to IS NULL OR active_to >= active_from),
    ADD CONSTRAINT ck_insurer_panel_line CHECK (line IN ('LIFE', 'NONLIFE')),
    -- 같은 보험사의 위탁 기간은 겹치지 않는다(기준일 판정이 단건)
    ADD CONSTRAINT ex_insurer_panel_overlap
        EXCLUDE USING gist (tenant_id WITH =, insurer_code WITH =, daterange(active_from, active_to, '[)') WITH &&);

CREATE FUNCTION ga_catalog_no_delete() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% rows are never deleted (%); close their validity period instead', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'GD070';
END
$$;

CREATE FUNCTION ga_product_catalog_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.product_key IS DISTINCT FROM OLD.product_key
        OR NEW.insurer_code IS DISTINCT FROM OLD.insurer_code THEN
        RAISE EXCEPTION 'product_catalog % identity (product key and insurer) is immutable', OLD.product_key
            USING ERRCODE = 'GD071';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_product_catalog_guard
    BEFORE UPDATE ON product_catalog
    FOR EACH ROW EXECUTE FUNCTION ga_product_catalog_guard();

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['product_group', 'product_catalog', 'insurer_panel']
    LOOP
        EXECUTE format('CREATE TRIGGER trg_%s_no_delete BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ga_catalog_no_delete()', t, t);
        EXECUTE format('CREATE TRIGGER trg_%s_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION ga_catalog_no_delete()', t, t);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 카탈로그 수입 이력(append-only): 같은 파일 재수입 판정(kind, file_sha256)과 asOf 단조성의 근거
-- ---------------------------------------------------------------------------------------------
CREATE TABLE catalog_import (
    tenant_id    TEXT NOT NULL,
    import_id    UUID NOT NULL,
    kind         TEXT NOT NULL,
    file_name    TEXT NOT NULL,
    file_sha256  TEXT NOT NULL,
    source       TEXT NOT NULL,
    as_of        DATE NOT NULL,
    imported_at  TIMESTAMPTZ NOT NULL,
    inserted     INT NOT NULL,
    updated      INT NOT NULL,
    closed       INT NOT NULL,
    unchanged    INT NOT NULL,
    PRIMARY KEY (tenant_id, import_id),
    CONSTRAINT uq_catalog_import_file UNIQUE (tenant_id, kind, file_sha256),
    CONSTRAINT ck_catalog_import_kind CHECK (kind IN ('PRODUCT_GROUPS', 'PRODUCTS', 'INSURER_PANEL')),
    CONSTRAINT ck_catalog_import_hash CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_catalog_import_counts CHECK (inserted >= 0 AND updated >= 0 AND closed >= 0 AND unchanged >= 0)
);

CREATE TRIGGER trg_catalog_import_append_only
    BEFORE UPDATE OR DELETE ON catalog_import
    FOR EACH ROW EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_catalog_import_no_truncate
    BEFORE TRUNCATE ON catalog_import
    FOR EACH STATEMENT EXECUTE FUNCTION ga_append_only();

-- ---------------------------------------------------------------------------------------------
-- 준법 플래그 대상: 같은 대상의 열린 플래그는 하나(일 배치 재실행이 플래그를 복제하지 않는다)
-- ---------------------------------------------------------------------------------------------
ALTER TABLE compliance_flag
    ADD COLUMN target_kind TEXT,                                           -- 예: RULE_VERSION, FORM_TEMPLATE
    ADD COLUMN target_id   TEXT,
    ADD CONSTRAINT ck_compliance_flag_target CHECK ((target_kind IS NULL) = (target_id IS NULL));
CREATE UNIQUE INDEX ux_compliance_flag_open_target ON compliance_flag (tenant_id, type, target_kind, target_id)
    WHERE resolved_at IS NULL AND target_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 새 테이블 RLS(V2와 같은 정책)·권한
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['customer_data_key', 'catalog_import']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I '
            'USING (tenant_id = current_setting(''app.tenant_id'', true)) '
            'WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true))', t);
        EXECUTE format('REVOKE ALL ON TABLE %I FROM PUBLIC', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %I TO disclosure_app', t);
    END LOOP;
END
$$;
