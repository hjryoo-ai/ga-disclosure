-- =============================================================================================
-- V7__seal.sql — 봉인: 봉인 컬럼–상태 결속, 채번 카운터, 봉인 체인 머리, 문서 데이터 키, 산출물 확장, 승인의 룰 버전 귀속,
-- 보존기한 단조 증가 (Phase 3B, 설계서 §5·§6.4·§6.6·§9, 3B 계획 §4·승인 Q6~Q8)
--
-- 봉인 컬럼 7개(disclosure_no, sealed_at, canonical_hash, pdf_hash, chain_hash, chain_seq, retention_until)는 전부 있거나 전부 없다.
-- 가변 상태(DRAFT~REASONED)는 전부 없고, VOID는 둘 다 허용(봉인 전 무효화 = 없음, 봉인 후 무효화 = 번호 유지, 승인 Q8), 나머지 봉인 이후
-- 상태는 전부 있다. 봉인 컬럼은 V3 메타 목록 밖이므로 봉인 뒤 자동으로 불변이다. retention_until만 메타이며 증가만 허용한다(GD094).
--
-- 봉인 정합(GD095, 봉인되는 순간 1회): 번호는 그 테넌트·연도 카운터의 현재 값이고(채번 직후 같은 트랜잭션), 연도는 봉인 시각의
-- Asia/Seoul 연도이며(승인 Q5), chain_seq = 체인 머리 + 1, chain_hash = SHA-256(prev ‖ canonical_hash ‖ pdf_hash)(소문자 hex ASCII를
-- 이어 붙인 바이트, 첫 봉인의 prev = '0' × 64). 애플리케이션 계산과 DB가 이중으로 같은 식을 강제한다.
-- 잠금 순서(승인 Q7): 확인서 행 → 카운터 행(테넌트, 연도) → 체인 머리 행(테넌트). 모든 봉인이 이 순서다.
--
-- 거부는 SQLSTATE 'GD0xx'(docs/db-error-codes.md):
--   GD081 review INSERT의 룰 버전 ≠ 부모 확인서의 고정 버전
--   GD090 disclosure_counter: 1이 아닌 첫 값, +1이 아닌 갱신·키 변경, DELETE·TRUNCATE
--   GD091 disclosure_chain_head: 1이 아닌 첫 값, +1이 아닌 갱신·키 변경, 확인서와 어긋난 머리, DELETE·TRUNCATE
--   GD092 document_key: 봉인 전 INSERT·파기된 채 INSERT, 파기 외 UPDATE, DELETE·TRUNCATE
--   GD093 document_artifact: 봉인 전 INSERT, 다른 확인서·파기된 키 참조, retention_applied_at NULL→값 1회 외 UPDATE
--   GD094 disclosure.retention_until 단축 또는 값 → NULL
--   GD095 봉인 정합(번호 ↔ 카운터·연도, 체인 ↔ 체인 머리)
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 1. 채번 카운터(무결번): 번호 채번·렌더·저장·커밋이 한 트랜잭션이고 이 행의 잠금이 테넌트·연도별 봉인을 직렬화한다(3A 수용심사 §3-4).
--    INSERT … ON CONFLICT (tenant_id, year) DO UPDATE SET seq = disclosure_counter.seq + 1 RETURNING seq. 선할당 없음.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE disclosure_counter (
    tenant_id  TEXT NOT NULL REFERENCES tenant (tenant_id),
    year       SMALLINT NOT NULL,
    seq        BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, year),
    CONSTRAINT ck_disclosure_counter_year CHECK (year BETWEEN 2000 AND 9999),
    CONSTRAINT ck_disclosure_counter_seq CHECK (seq BETWEEN 1 AND 999999)
);

CREATE FUNCTION ga_disclosure_counter_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.seq <> 1 THEN
            RAISE EXCEPTION 'disclosure_counter % % starts at 1 (got %)', NEW.tenant_id, NEW.year, NEW.seq
                USING ERRCODE = 'GD090';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.year IS DISTINCT FROM OLD.year OR NEW.seq <> OLD.seq + 1 THEN
            RAISE EXCEPTION 'disclosure_counter % % advances by exactly one (% -> %)', OLD.tenant_id, OLD.year, OLD.seq, NEW.seq
                USING ERRCODE = 'GD090', HINT = 'numbers are gapless: never skip, rewind or pre-allocate';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'disclosure_counter rows are never removed (%)', TG_OP
        USING ERRCODE = 'GD090';
END
$$;

CREATE TRIGGER trg_disclosure_counter_guard
    BEFORE INSERT OR UPDATE OR DELETE ON disclosure_counter
    FOR EACH ROW EXECUTE FUNCTION ga_disclosure_counter_guard();
CREATE TRIGGER trg_disclosure_counter_no_truncate
    BEFORE TRUNCATE ON disclosure_counter
    FOR EACH STATEMENT EXECUTE FUNCTION ga_disclosure_counter_guard();

-- ---------------------------------------------------------------------------------------------
-- 2. 봉인 체인 머리(테넌트당 1행, 승인 Q7): 체인은 테넌트 단위이고 카운터는 (테넌트, 연도) 단위라 연말 경계에서 max(chain_seq)는
--    경합한다. 머리 행을 FOR UPDATE로 잠근다. 머리는 언제나 그 테넌트의 마지막 봉인을 가리킨다.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE disclosure_chain_head (
    tenant_id   TEXT PRIMARY KEY REFERENCES tenant (tenant_id),
    chain_seq   BIGINT NOT NULL,
    chain_hash  TEXT NOT NULL,
    CONSTRAINT ck_disclosure_chain_head_seq CHECK (chain_seq >= 1),
    CONSTRAINT ck_disclosure_chain_head_hash CHECK (chain_hash ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION ga_disclosure_chain_head_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'disclosure_chain_head rows are never removed (%)', TG_OP
            USING ERRCODE = 'GD091';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.chain_seq <> 1 THEN
        RAISE EXCEPTION 'disclosure_chain_head % starts at 1 (got %)', NEW.tenant_id, NEW.chain_seq
            USING ERRCODE = 'GD091';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.chain_seq <> OLD.chain_seq + 1) THEN
        RAISE EXCEPTION 'disclosure_chain_head % advances by exactly one (% -> %)', OLD.tenant_id, OLD.chain_seq, NEW.chain_seq
            USING ERRCODE = 'GD091';
    END IF;
    -- 머리는 실재하는 봉인을 가리킨다(봉인 UPDATE 뒤에 머리를 옮긴다)
    IF NOT EXISTS (SELECT 1 FROM disclosure d
                    WHERE d.tenant_id = NEW.tenant_id AND d.chain_seq = NEW.chain_seq AND d.chain_hash = NEW.chain_hash) THEN
        RAISE EXCEPTION 'disclosure_chain_head % (%) does not point at a sealed disclosure', NEW.tenant_id, NEW.chain_seq
            USING ERRCODE = 'GD091';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_disclosure_chain_head_guard
    BEFORE INSERT OR UPDATE OR DELETE ON disclosure_chain_head
    FOR EACH ROW EXECUTE FUNCTION ga_disclosure_chain_head_guard();
CREATE TRIGGER trg_disclosure_chain_head_no_truncate
    BEFORE TRUNCATE ON disclosure_chain_head
    FOR EACH STATEMENT EXECUTE FUNCTION ga_disclosure_chain_head_guard();

-- ---------------------------------------------------------------------------------------------
-- 3. disclosure: 봉인 컬럼–상태 결속(3A에서 미룸), 형식, VOID·SUPERSEDED 일관성, 고정 룰 필수, 체인 순번 유일
-- (CHECK는 식이 NULL이면 통과한다 — 아래 식은 NULL을 IS [NOT] NULL·coalesce로만 다룬다. SealColumnCheckIT가 전수 검사)
-- ---------------------------------------------------------------------------------------------
ALTER TABLE disclosure
    ADD CONSTRAINT ck_disclosure_seal_all_or_none CHECK (
        (disclosure_no IS NULL AND sealed_at IS NULL AND canonical_hash IS NULL AND pdf_hash IS NULL
         AND chain_hash IS NULL AND chain_seq IS NULL AND retention_until IS NULL)
        OR (disclosure_no IS NOT NULL AND sealed_at IS NOT NULL AND canonical_hash IS NOT NULL AND pdf_hash IS NOT NULL
            AND chain_hash IS NOT NULL AND chain_seq IS NOT NULL AND retention_until IS NOT NULL)),
    ADD CONSTRAINT ck_disclosure_seal_by_status CHECK (CASE
        WHEN ga_is_mutable_status(status) THEN disclosure_no IS NULL
        WHEN status = 'VOID' THEN true                                       -- 봉인 전 무효화는 번호가 없다(승인 Q8)
        ELSE disclosure_no IS NOT NULL END),                                 -- SEALED·PARTIALLY_SIGNED·COMPLETED·SUPERSEDED·EXPIRED
    ADD CONSTRAINT ck_disclosure_no_format CHECK (disclosure_no IS NULL OR coalesce(
        disclosure_no ~ '^[A-Z0-9][A-Z0-9_]{0,31}-[0-9]{4}-[0-9]{6}$' AND split_part(disclosure_no, '-', 1) = tenant_id, false)),
    ADD CONSTRAINT ck_disclosure_seal_hashes CHECK (
        (canonical_hash IS NULL OR canonical_hash ~ '^[0-9a-f]{64}$')
        AND (pdf_hash IS NULL OR pdf_hash ~ '^[0-9a-f]{64}$')
        AND (chain_hash IS NULL OR chain_hash ~ '^[0-9a-f]{64}$')),
    ADD CONSTRAINT ck_disclosure_chain_seq CHECK (chain_seq IS NULL OR chain_seq >= 1),
    ADD CONSTRAINT ck_disclosure_void CHECK (
        (status = 'VOID') = (voided_at IS NOT NULL)
        AND (voided_at IS NULL) = (void_reason IS NULL)
        AND (void_reason IS NULL OR btrim(void_reason) <> '')),
    ADD CONSTRAINT ck_disclosure_superseded CHECK ((status = 'SUPERSEDED') = (superseded_by_id IS NOT NULL)),
    ADD CONSTRAINT ck_disclosure_pinned_rule CHECK (rule_version_id IS NOT NULL),     -- 3A부터 초안 생성 때 고정(애플리케이션과 이중)
    ADD CONSTRAINT ux_disclosure_chain_seq UNIQUE (tenant_id, chain_seq);

-- 봉인 정합(GD095). 행이 봉인 컬럼을 처음 갖는 순간(INSERT 또는 봉인 컬럼이 비어 있던 행의 UPDATE)에만 본다. 봉인 컬럼 일부만 있는 행은
-- 여기서 판단하지 않고 ck_disclosure_seal_all_or_none이 거부한다. 트리거 이름은 V3 trg_disclosure_guard_update보다 뒤에 정렬된다
-- (같은 시점 BEFORE 트리거는 이름 순) — 봉인된 행의 본문 변경은 GD001이 먼저 거부한다.
CREATE FUNCTION ga_disclosure_seal_integrity() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    head_seq   BIGINT;
    head_hash  TEXT;
    counter    BIGINT;
    no_year    INT;
    no_seq     INT;
    expected   TEXT;
BEGIN
    IF NEW.disclosure_no IS NULL OR NEW.sealed_at IS NULL OR NEW.canonical_hash IS NULL OR NEW.pdf_hash IS NULL
        OR NEW.chain_hash IS NULL OR NEW.chain_seq IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.chain_seq IS NOT NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.disclosure_no !~ '^[A-Z0-9][A-Z0-9_]{0,31}-[0-9]{4}-[0-9]{6}$' THEN
        RETURN NEW;                                                          -- 형식은 ck_disclosure_no_format이 거부한다
    END IF;
    no_year := split_part(NEW.disclosure_no, '-', 2)::INT;
    no_seq := split_part(NEW.disclosure_no, '-', 3)::INT;
    IF no_year <> extract(YEAR FROM (NEW.sealed_at AT TIME ZONE 'Asia/Seoul'))::INT THEN
        RAISE EXCEPTION 'disclosure_no % year is not the Asia/Seoul year of sealed_at', NEW.disclosure_no
            USING ERRCODE = 'GD095';
    END IF;
    SELECT c.seq INTO counter
      FROM disclosure_counter c
     WHERE c.tenant_id = NEW.tenant_id AND c.year = no_year;
    IF NOT FOUND OR counter <> no_seq THEN
        RAISE EXCEPTION 'disclosure_no % is not the current counter value of % %', NEW.disclosure_no, NEW.tenant_id, no_year
            USING ERRCODE = 'GD095', HINT = 'issue the number from disclosure_counter in the sealing transaction';
    END IF;
    SELECT h.chain_seq, h.chain_hash INTO head_seq, head_hash
      FROM disclosure_chain_head h
     WHERE h.tenant_id = NEW.tenant_id
       FOR UPDATE;
    IF NOT FOUND THEN
        head_seq := 0;
        head_hash := repeat('0', 64);
    END IF;
    expected := encode(sha256(convert_to(head_hash || NEW.canonical_hash || NEW.pdf_hash, 'UTF8')), 'hex');
    IF NEW.chain_seq <> head_seq + 1 OR NEW.chain_hash <> expected THEN
        RAISE EXCEPTION 'disclosure % chain (seq %) does not extend the chain head (seq %)', NEW.disclosure_id, NEW.chain_seq, head_seq
            USING ERRCODE = 'GD095', HINT = 'chain_hash = SHA-256(prev_chain_hash || canonical_hash || pdf_hash)';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_disclosure_seal_integrity
    BEFORE INSERT OR UPDATE ON disclosure
    FOR EACH ROW EXECUTE FUNCTION ga_disclosure_seal_integrity();

-- 보존기한은 봉인 때 정하고(봉인일 + retentionYears, 승인 Q6) 이후 연장만 한다 — Object Lock COMPLIANCE의 "연장만"과 같은 의미.
CREATE FUNCTION ga_disclosure_retention_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'disclosure % retention_until can only be extended (% -> %)', OLD.disclosure_id, OLD.retention_until, NEW.retention_until
        USING ERRCODE = 'GD094';
END
$$;

CREATE TRIGGER trg_disclosure_retention_guard
    BEFORE UPDATE ON disclosure
    FOR EACH ROW
    WHEN (OLD.retention_until IS NOT NULL AND (NEW.retention_until IS NULL OR NEW.retention_until < OLD.retention_until))
    EXECUTE FUNCTION ga_disclosure_retention_guard();

-- ---------------------------------------------------------------------------------------------
-- 4. 문서 데이터 키(crypto-shredding, 3A 수용심사 §3-5): 확인서마다 DEK 1개를 테넌트 KEK로 감싸 둔다. 파기 = wrapped_dek NULL.
--    Phase 4의 SIGNED_PDF·EVIDENCE_ZIP도 같은 키를 쓴다(문서당 1개).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE document_key (
    tenant_id      TEXT NOT NULL,
    key_id         TEXT NOT NULL,
    disclosure_id  UUID NOT NULL,
    kek_key_id     TEXT NOT NULL,
    wrapped_dek    BYTEA,
    created_at     TIMESTAMPTZ NOT NULL,
    shredded_at    TIMESTAMPTZ,
    shredded_by    TEXT,
    PRIMARY KEY (tenant_id, key_id),
    CONSTRAINT uq_document_key_disclosure UNIQUE (tenant_id, disclosure_id),
    CONSTRAINT fk_document_key_disclosure FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure (tenant_id, disclosure_id),
    CONSTRAINT ck_document_key_id CHECK (key_id ~ '^DOC-[0-9a-f]{32}$'),
    CONSTRAINT ck_document_key_shred CHECK ((shredded_at IS NULL) = (wrapped_dek IS NOT NULL)
                                            AND (shredded_at IS NULL) = (shredded_by IS NULL)),
    CONSTRAINT ck_document_key_wrapped CHECK (wrapped_dek IS NULL OR octet_length(wrapped_dek) >= 29)   -- 0x01 ‖ nonce 12 ‖ … ‖ tag 16
);

CREATE FUNCTION ga_document_key_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_no TEXT;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'document_key rows are never removed (%); shred the key material instead', TG_OP
            USING ERRCODE = 'GD092';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT d.disclosure_no INTO parent_no
          FROM disclosure d
         WHERE d.tenant_id = NEW.tenant_id AND d.disclosure_id = NEW.disclosure_id
           FOR SHARE;
        IF parent_no IS NULL THEN
            RAISE EXCEPTION 'document_key for disclosure % may only be created once it is sealed', NEW.disclosure_id
                USING ERRCODE = 'GD092';
        END IF;
        IF NEW.wrapped_dek IS NULL THEN
            RAISE EXCEPTION 'document_key % is created with key material', NEW.key_id
                USING ERRCODE = 'GD092';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: 파기만(감싼 키 → NULL, 파기 시각·주체 기록), 파기 전용 함수(소유 롤)만
    IF current_user <> 'disclosure_migrator'
        OR OLD.wrapped_dek IS NULL OR NEW.wrapped_dek IS NOT NULL
        OR (to_jsonb(NEW) - ARRAY['wrapped_dek', 'shredded_at', 'shredded_by'])
           IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['wrapped_dek', 'shredded_at', 'shredded_by']) THEN
        RAISE EXCEPTION 'document_key % changes only by shredding through ga_shred_document_key', OLD.key_id
            USING ERRCODE = 'GD092';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_document_key_guard
    BEFORE INSERT OR UPDATE OR DELETE ON document_key
    FOR EACH ROW EXECUTE FUNCTION ga_document_key_guard();
CREATE TRIGGER trg_document_key_no_truncate
    BEFORE TRUNCATE ON document_key
    FOR EACH STATEMENT EXECUTE FUNCTION ga_document_key_guard();

-- 파기 경로 예약(Phase 5 파기 배치가 전용 롤에 EXECUTE를 준다. 지금은 누구에게도 주지 않고, S8 테스트가 소유 롤로 호출한다).
-- SECURITY DEFINER(소유자 disclosure_migrator) — 함수 안에서만 테넌트를 트랜잭션 로컬로 바인딩하고 나올 때 되돌린다.
CREATE FUNCTION ga_shred_document_key(p_tenant TEXT, p_disclosure UUID, p_at TIMESTAMPTZ, p_by TEXT) RETURNS TEXT
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    previous TEXT := current_setting('app.tenant_id', true);
    shredded TEXT;
BEGIN
    IF p_tenant IS NULL OR p_disclosure IS NULL OR p_at IS NULL OR p_by IS NULL OR btrim(p_by) = '' THEN
        RAISE EXCEPTION 'tenant, disclosure, time and actor are required' USING ERRCODE = '22004';
    END IF;
    PERFORM set_config('app.tenant_id', p_tenant, true);
    UPDATE document_key
       SET wrapped_dek = NULL, shredded_at = p_at, shredded_by = p_by
     WHERE tenant_id = p_tenant AND disclosure_id = p_disclosure AND wrapped_dek IS NOT NULL
    RETURNING key_id INTO shredded;
    PERFORM set_config('app.tenant_id', coalesce(previous, ''), true);
    RETURN shredded;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 5. document_artifact 확장(3A 수용심사 §3-5): sha256 = 평문 해시(체인·검증용), cipher_sha256 = 저장된 바이트의 해시.
--    객체 키 = {tenant}/{disclosure}/{kind}/{cipher_sha256}(승인 Q4 — 버킷 목록에 평문 해시가 없고 재시도마다 새 키).
--    Object Lock은 커밋 후 적용하고 retention_applied_at을 1회 기록한다. 테이블은 비어 있다(봉인 코드가 처음 쓴다).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE document_artifact
    ADD COLUMN cipher_sha256         TEXT NOT NULL,
    ADD COLUMN cipher_bytes          BIGINT NOT NULL,
    ADD COLUMN key_id                TEXT NOT NULL,
    ADD COLUMN retention_applied_at  TIMESTAMPTZ,
    ADD CONSTRAINT fk_document_artifact_disclosure FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure (tenant_id, disclosure_id),
    ADD CONSTRAINT fk_document_artifact_key FOREIGN KEY (tenant_id, key_id) REFERENCES document_key (tenant_id, key_id),
    ADD CONSTRAINT ck_document_artifact_kind CHECK (kind IN ('CANONICAL_JSON', 'PDF', 'SIGNED_PDF', 'EVIDENCE_ZIP')),
    ADD CONSTRAINT ck_document_artifact_hashes CHECK (sha256 ~ '^[0-9a-f]{64}$' AND cipher_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_document_artifact_sizes CHECK (bytes >= 1 AND cipher_bytes = bytes + 29),   -- 0x01 ‖ nonce 12 ‖ 암호문 ‖ tag 16
    ADD CONSTRAINT ck_document_artifact_storage_key CHECK (
        storage_key = tenant_id || '/' || disclosure_id::text || '/' || kind || '/' || cipher_sha256);

-- V3의 전면 append-only(UPDATE·DELETE)를 아래로 교체한다(V3 파일은 수정하지 않는다). DELETE·TRUNCATE는 GD030 그대로.
DROP TRIGGER trg_document_artifact_append_only ON document_artifact;
CREATE TRIGGER trg_document_artifact_no_delete
    BEFORE DELETE ON document_artifact
    FOR EACH ROW EXECUTE FUNCTION ga_append_only();

CREATE FUNCTION ga_document_artifact_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_no      TEXT;
    key_disclosure UUID;
    key_material   BYTEA;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT d.disclosure_no INTO parent_no
          FROM disclosure d
         WHERE d.tenant_id = NEW.tenant_id AND d.disclosure_id = NEW.disclosure_id
           FOR SHARE;
        IF parent_no IS NULL THEN
            RAISE EXCEPTION 'document_artifact for disclosure % may only be recorded once it is sealed', NEW.disclosure_id
                USING ERRCODE = 'GD093';
        END IF;
        SELECT k.disclosure_id, k.wrapped_dek INTO key_disclosure, key_material
          FROM document_key k
         WHERE k.tenant_id = NEW.tenant_id AND k.key_id = NEW.key_id;
        IF key_disclosure IS DISTINCT FROM NEW.disclosure_id OR key_material IS NULL THEN
            RAISE EXCEPTION 'document_artifact % of disclosure % must use that disclosure''s live document key', NEW.kind, NEW.disclosure_id
                USING ERRCODE = 'GD093';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: Object Lock 적용 기록 1회만
    IF OLD.retention_applied_at IS NOT NULL OR NEW.retention_applied_at IS NULL
        OR (to_jsonb(NEW) - 'retention_applied_at') IS DISTINCT FROM (to_jsonb(OLD) - 'retention_applied_at') THEN
        RAISE EXCEPTION 'document_artifact % of disclosure % changes only by recording retention once', OLD.kind, OLD.disclosure_id
            USING ERRCODE = 'GD093';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_document_artifact_guard
    BEFORE INSERT OR UPDATE ON document_artifact
    FOR EACH ROW EXECUTE FUNCTION ga_document_artifact_guard();

-- ---------------------------------------------------------------------------------------------
-- 6. review: 승인의 룰 버전 귀속(3A 수용심사 §3-8). 승인은 "지금 고정된 룰 아래의 실패"에만 기록할 수 있고(GD081), 봉인 판정(SealGate)은
--    확인서의 현재 고정 버전과 같은 승인만 인정한다. 재기준 뒤 옛 승인은 삭제되지 않고 귀속으로 무효가 된다.
-- 기존 행 백필: 3A까지 고정 ID는 초안 이후 바뀐 적이 없다(재기준 명령이 없었다) — 부모의 현재 고정 ID가 곧 승인 당시 ID다.
-- 소유 롤도 FORCE RLS 대상이라 백필 동안만 두 테이블의 FORCE를 풀고, append-only 트리거도 이 블록 동안만 끈다(같은 트랜잭션).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE review
    ADD COLUMN rule_version_id        TEXT,
    ADD COLUMN tenant_rule_version_id TEXT;

ALTER TABLE review DISABLE TRIGGER trg_review_append_only;
ALTER TABLE review NO FORCE ROW LEVEL SECURITY;
ALTER TABLE disclosure NO FORCE ROW LEVEL SECURITY;
UPDATE review r
   SET rule_version_id = d.rule_version_id, tenant_rule_version_id = d.tenant_rule_version_id
  FROM disclosure d
 WHERE d.tenant_id = r.tenant_id AND d.disclosure_id = r.disclosure_id;
ALTER TABLE disclosure FORCE ROW LEVEL SECURITY;
ALTER TABLE review FORCE ROW LEVEL SECURITY;
ALTER TABLE review ENABLE TRIGGER trg_review_append_only;

ALTER TABLE review ALTER COLUMN rule_version_id SET NOT NULL;

CREATE OR REPLACE FUNCTION ga_review_guard_insert() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_status      TEXT;
    parent_rule        TEXT;
    parent_tenant_rule TEXT;
BEGIN
    SELECT d.status, d.rule_version_id, d.tenant_rule_version_id INTO parent_status, parent_rule, parent_tenant_rule
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
    IF NEW.rule_version_id IS DISTINCT FROM parent_rule OR NEW.tenant_rule_version_id IS DISTINCT FROM parent_tenant_rule THEN
        RAISE EXCEPTION 'review of disclosure % must carry its pinned rule versions', NEW.disclosure_id
            USING ERRCODE = 'GD081';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 7. RLS(V2와 같은 정책)·권한 — 새 테이블은 필요한 동사만
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['disclosure_counter', 'disclosure_chain_head', 'document_key']
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
GRANT SELECT, INSERT, UPDATE ON TABLE disclosure_counter TO disclosure_app;
GRANT SELECT, INSERT, UPDATE ON TABLE disclosure_chain_head TO disclosure_app;
GRANT SELECT, INSERT ON TABLE document_key TO disclosure_app;                 -- 파기는 ga_shred_document_key(EXECUTE 부여 없음)

-- 산출물: 삽입과 Object Lock 적용 기록만(V2가 준 UPDATE·DELETE 전체 권한을 거둔다)
REVOKE UPDATE, DELETE ON TABLE document_artifact FROM disclosure_app;
GRANT UPDATE (retention_applied_at) ON TABLE document_artifact TO disclosure_app;

REVOKE ALL ON FUNCTION ga_disclosure_counter_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_disclosure_chain_head_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_disclosure_seal_integrity() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_disclosure_retention_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_document_key_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_document_artifact_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_shred_document_key(TEXT, UUID, TIMESTAMPTZ, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_review_guard_insert() FROM PUBLIC;
