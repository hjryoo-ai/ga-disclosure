-- =============================================================================================
-- V8__sign.sql — 서명: 세션·두 해시 귀속·서명 증거 객체·보존 재적용·아웃박스, 무효·정정 사유 코드
-- (Phase 4, 설계서 §4.5·§5·§6.5·§6.6·§9, 4 계획 §1·승인 Q2·Q3·Q7·Q13, 3B 수용심사 §2-2·§3)
--
-- 서명은 사람이 아니라 문서에 귀속된다: signed_doc_hash = disclosure.canonical_hash(절대 규칙 3, V3 GD022)에 더해
-- signed_pdf_hash = disclosure.pdf_hash(서명자가 본 것은 PDF 바이트, GD102). 고객 서명은 세션을 거치고 세션은 발급 시 두 해시를
-- 고정한다(GD103). 설계사·관리자는 SSO 행위자로 직접 서명하고 채널은 SSO다(승인 Q3 — 채널 = 도달 경로, method = 행위).
-- 서명 증거 객체(스트로크·이미지·스캔)는 signature_evidence에 1객체 1행(승인 Q2): 문서 DEK로 암호화하고 보존 재적용을 행마다 기록한다.
-- 보존기한은 연장만(V7 GD094 그대로). 완료 때 연장되면 모든 객체에 잠금을 다시 걸어야 하므로 적용 기한(retention_applied_until)을
-- 기록하고 증가만 허용한다. 아웃박스는 테넌트 내 갭 없는 seq(계약 envelope)이고 머리 행으로 직렬화한다(체인 머리와 같은 방식).
--
-- 잠금 순서(3B 승인 Q7 확장): 확인서 행 → 카운터 행 → 체인 머리 행 → 아웃박스 머리 행.
--
-- 거부는 SQLSTATE 'GD1xx'(docs/db-error-codes.md):
--   GD100 disclosure 무효·정정 사유(코드·텍스트)·voided_at·completed_at 재기록(한 번 쓰면 끝)
--   GD101 sign_session: 발급 조건 위반, 고정 컬럼 변경, OPEN 밖에서의 변경, 허용되지 않은 갱신, DELETE·TRUNCATE
--   GD102 signature INSERT: signed_pdf_hash ≠ disclosure.pdf_hash
--   GD103 signature INSERT: 세션이 같은 확인서·역할의 OPEN 세션이 아니거나 세션 고정 해시 ≠ 서명 해시
--   GD104 signature INSERT: 역할이 고정 GLOBAL 룰 signerSet에 없음(managerConfirmMode=OPTIONAL의 MANAGER는 허용)
--   GD105 signature_evidence: 서명·확인서 불일치, 문서 키 위반, 보존 기록 외 변경, DELETE·TRUNCATE
--   GD106 outbox_head·outbox_event: 갭 있는 seq, +1이 아닌 머리 갱신, published_at 1회 외 변경, DELETE·TRUNCATE
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 1. disclosure: 무효·정정 사유 코드 + 텍스트(3B 수용심사 §2-2), 완료 시각 정합
--    사유 코드는 고정 룰의 voidReasons·supersedeReasons 닫힌 목록 — 목록 대조는 애플리케이션(DB는 룰 본문을 해석하지 않는다).
--    기존 void_reason은 이관 후 더는 쓰지 않는다(NULL 허용, V9에서 DROP — 전방 호환 2단계).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE disclosure
    ADD COLUMN void_reason_code      TEXT,
    ADD COLUMN void_reason_text      TEXT,
    ADD COLUMN supersede_reason_code TEXT,
    ADD COLUMN supersede_reason_text TEXT;

-- 메타 목록에 새 사유 컬럼 4개를 넣는다(봉인 뒤 VOID·SUPERSEDE 전이 때 한 번 쓴다). 나머지 규칙은 V3 그대로이고 한 번 쓰기 검사를 더한다.
CREATE OR REPLACE FUNCTION ga_disclosure_guard_update() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    meta CONSTANT TEXT[] := ARRAY['status', 'superseded_by_id', 'completed_at', 'voided_at', 'void_reason',
                                  'void_reason_code', 'void_reason_text', 'supersede_reason_code', 'supersede_reason_text',
                                  'policy_no', 'contract_date', 'retention_until'];
BEGIN
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

-- 이관(개발·CI DB에만 행이 있다): 무효 사유 원문 → 텍스트, 코드는 이관 표지. 정정 원본은 사유가 저장된 적이 없다(감사에 해시·길이만).
-- 소유 롤도 FORCE RLS 대상이라 이 블록 동안만 FORCE를 푼다(V7과 같은 방식, 같은 트랜잭션). 이관도 테넌트 단위로 묶어 쓴다(규칙 5 스캔).
ALTER TABLE disclosure NO FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
ALTER TABLE disclosure DROP CONSTRAINT ck_disclosure_void;
UPDATE disclosure d SET void_reason_code = 'MIGRATED_V8', void_reason_text = d.void_reason, void_reason = NULL
  FROM tenant t
 WHERE d.tenant_id = t.tenant_id AND d.status = 'VOID';
UPDATE disclosure d SET supersede_reason_code = 'MIGRATED_V8'
  FROM tenant t
 WHERE d.tenant_id = t.tenant_id AND d.status = 'SUPERSEDED';
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
ALTER TABLE disclosure FORCE ROW LEVEL SECURITY;

ALTER TABLE disclosure
    DROP CONSTRAINT ck_disclosure_superseded,
    ADD CONSTRAINT ck_disclosure_void CHECK (
        (status = 'VOID') = (voided_at IS NOT NULL)
        AND (voided_at IS NULL) = (void_reason_code IS NULL)
        AND (void_reason_code IS NOT NULL OR void_reason_text IS NULL)),
    ADD CONSTRAINT ck_disclosure_superseded CHECK (
        (status = 'SUPERSEDED') = (superseded_by_id IS NOT NULL)
        AND (superseded_by_id IS NULL) = (supersede_reason_code IS NULL)
        AND (supersede_reason_code IS NOT NULL OR supersede_reason_text IS NULL)),
    ADD CONSTRAINT ck_disclosure_reason_format CHECK (
        (void_reason_code IS NULL OR void_reason_code ~ '^[A-Z][A-Z0-9_]{0,39}$')
        AND (supersede_reason_code IS NULL OR supersede_reason_code ~ '^[A-Z][A-Z0-9_]{0,39}$')
        AND (void_reason_text IS NULL OR btrim(void_reason_text) <> '')
        AND (supersede_reason_text IS NULL OR btrim(supersede_reason_text) <> '')),
    ADD CONSTRAINT ck_disclosure_legacy_void_reason CHECK (void_reason IS NULL),   -- V9에서 컬럼 제거(이관 뒤 쓰지 않는다)
    ADD CONSTRAINT ck_disclosure_completed CHECK (
        (status <> 'COMPLETED' OR completed_at IS NOT NULL)
        AND (completed_at IS NULL OR status IN ('COMPLETED', 'VOID', 'SUPERSEDED')));

-- ---------------------------------------------------------------------------------------------
-- 2. tenant.params에서 gateRequiresManager 제거(승인 Q7 — 출처는 룰 데이터 하나, tenantOverridable). V1 파일은 고치지 않는다.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
UPDATE tenant SET params = params - 'gateRequiresManager' WHERE params ? 'gateRequiresManager';
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant ALTER COLUMN params SET DEFAULT '{}'::jsonb;

-- ---------------------------------------------------------------------------------------------
-- 3. sign_session: 고객 서명 세션(4 계획 §2). 토큰은 SHA-256만, 발급 시 두 해시를 고정한다. 상태는 OPEN → 나머지 1회.
--    행은 지금까지 쓴 곳이 없다(행 0) — NOT NULL 컬럼을 기본값 없이 더한다.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE sign_session
    ALTER COLUMN token_hash SET NOT NULL,
    ADD COLUMN issued_by          TEXT NOT NULL,
    ADD COLUMN issued_at          TIMESTAMPTZ NOT NULL,
    ADD COLUMN sent_at            TIMESTAMPTZ,
    ADD COLUMN signed_doc_hash    TEXT NOT NULL,
    ADD COLUMN signed_pdf_hash    TEXT NOT NULL,
    ADD COLUMN identity_failures  SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN identity_passed    TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN view_evidence      JSONB,
    ADD COLUMN revoked_at         TIMESTAMPTZ,
    ADD COLUMN revoke_reason      TEXT,
    ADD CONSTRAINT fk_sign_session_disclosure FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure (tenant_id, disclosure_id),
    ADD CONSTRAINT ck_sign_session_role CHECK (signer_role = 'CUSTOMER'),          -- 설계사·관리자는 SSO로 직접 서명(승인 Q3)
    ADD CONSTRAINT ck_sign_session_channel CHECK (channel IN ('TOUCH_PAD', 'REMOTE_LINK', 'PAPER_SCAN')),   -- CERTIFIED_ESIGN은 v2
    ADD CONSTRAINT ck_sign_session_status CHECK (status IN ('OPEN', 'USED', 'EXPIRED', 'REVOKED')),
    ADD CONSTRAINT ck_sign_session_hashes CHECK (
        token_hash ~ '^[0-9a-f]{64}$' AND signed_doc_hash ~ '^[0-9a-f]{64}$' AND signed_pdf_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_sign_session_used CHECK ((status = 'USED') = (used_at IS NOT NULL)),
    ADD CONSTRAINT ck_sign_session_revoked CHECK (
        (status = 'REVOKED') = (revoked_at IS NOT NULL)
        AND (revoked_at IS NULL) = (revoke_reason IS NULL)
        AND (revoke_reason IS NULL OR revoke_reason IN
             ('IDENTITY_FAILED', 'DOCUMENT_VOIDED', 'DOCUMENT_SUPERSEDED', 'DOCUMENT_EXPIRED', 'REISSUED'))),
    ADD CONSTRAINT ck_sign_session_sent CHECK (sent_at IS NULL OR channel = 'REMOTE_LINK'),
    ADD CONSTRAINT ck_sign_session_failures CHECK (identity_failures >= 0),
    ADD CONSTRAINT ck_sign_session_window CHECK (expires_at > issued_at),
    ADD CONSTRAINT ck_sign_session_passed CHECK (identity_passed <@ ARRAY['LINK_POSSESSION', 'BIRTH_DATE', 'AGENT_FACE_TO_FACE',
                                                                         'SCROLL_COMPLETE', 'PROVIDER']::TEXT[]);

CREATE UNIQUE INDEX ux_sign_session_open ON sign_session (tenant_id, disclosure_id, signer_role) WHERE status = 'OPEN';
CREATE UNIQUE INDEX ux_sign_session_token ON sign_session (tenant_id, token_hash);

CREATE FUNCTION ga_sign_session_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_status TEXT;
    parent_doc    TEXT;
    parent_pdf    TEXT;
    fixed CONSTANT TEXT[] := ARRAY['tenant_id', 'session_id', 'disclosure_id', 'signer_role', 'channel', 'token_hash', 'issued_by',
                                   'issued_at', 'expires_at', 'signed_doc_hash', 'signed_pdf_hash'];
BEGIN
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
       IS DISTINCT FROM (SELECT jsonb_object_agg(k, to_jsonb(OLD) -> k) FROM unnest(fixed) k) THEN
        RAISE EXCEPTION 'sign_session % identity and pinned columns are immutable', OLD.session_id
            USING ERRCODE = 'GD101';
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

CREATE TRIGGER trg_sign_session_guard
    BEFORE INSERT OR UPDATE OR DELETE ON sign_session
    FOR EACH ROW EXECUTE FUNCTION ga_sign_session_guard();
CREATE TRIGGER trg_sign_session_no_truncate
    BEFORE TRUNCATE ON sign_session
    FOR EACH STATEMENT EXECUTE FUNCTION ga_sign_session_guard();

-- ---------------------------------------------------------------------------------------------
-- 4. signature: 두 해시 귀속(3B 수용심사 §3-1), 세션 연결, 결과만 남는 본인확인, 관리자 사유 확인, 종이 스캔 대조.
--    증거 객체는 signature_evidence로 옮긴다(승인 Q2). 행은 지금까지 쓴 곳이 없다(행 0).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE signature
    DROP COLUMN evidence_key,
    DROP COLUMN evidence_hash,
    ALTER COLUMN identity_check SET NOT NULL,
    ADD COLUMN signed_pdf_hash     TEXT NOT NULL,
    ADD COLUMN session_id          UUID,
    ADD COLUMN view_evidence       JSONB,
    ADD COLUMN acknowledged_flags  UUID[] NOT NULL DEFAULT '{}',
    ADD COLUMN scan_match          JSONB,
    ADD CONSTRAINT fk_signature_disclosure FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure (tenant_id, disclosure_id),
    ADD CONSTRAINT fk_signature_session FOREIGN KEY (tenant_id, session_id) REFERENCES sign_session (tenant_id, session_id),
    ADD CONSTRAINT ck_signature_role CHECK (signer_role IN ('CUSTOMER', 'AGENT', 'MANAGER')),
    ADD CONSTRAINT ck_signature_channel CHECK (channel IN ('TOUCH_PAD', 'REMOTE_LINK', 'PAPER_SCAN', 'SSO')),
    ADD CONSTRAINT ck_signature_method CHECK (method IN ('DRAWN', 'UPLOADED_SCAN', 'SSO_APPROVAL')),
    ADD CONSTRAINT ck_signature_hashes CHECK (signed_doc_hash ~ '^[0-9a-f]{64}$' AND signed_pdf_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_signature_who CHECK (
        (signer_role = 'CUSTOMER') = (session_id IS NOT NULL)
        AND (signer_role = 'CUSTOMER') = (signer_subject IS NULL)
        AND (signer_role = 'CUSTOMER') = (channel <> 'SSO')),
    ADD CONSTRAINT ck_signature_method_channel CHECK (
        (method = 'UPLOADED_SCAN') = (channel = 'PAPER_SCAN')
        AND (method <> 'SSO_APPROVAL' OR channel = 'SSO')
        AND (scan_match IS NULL) = (channel <> 'PAPER_SCAN')),
    ADD CONSTRAINT ck_signature_identity CHECK (jsonb_typeof(identity_check) = 'array'),
    ADD CONSTRAINT ck_signature_ack CHECK (cardinality(acknowledged_flags) = 0 OR signer_role = 'MANAGER');

CREATE OR REPLACE FUNCTION ga_signature_guard_insert() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_status TEXT;
    parent_hash   TEXT;
    parent_pdf    TEXT;
    parent_rule   TEXT;
    signer_set    JSONB;
    manager_mode  TEXT;
    s             RECORD;
BEGIN
    SELECT d.status, d.canonical_hash, d.pdf_hash, d.rule_version_id INTO parent_status, parent_hash, parent_pdf, parent_rule
      FROM disclosure d
     WHERE d.tenant_id = NEW.tenant_id AND d.disclosure_id = NEW.disclosure_id
       FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'signature refers to missing disclosure %', NEW.disclosure_id
            USING ERRCODE = 'GD020';
    END IF;
    IF parent_status NOT IN ('SEALED', 'PARTIALLY_SIGNED') THEN
        RAISE EXCEPTION 'disclosure % in status % cannot be signed', NEW.disclosure_id, parent_status
            USING ERRCODE = 'GD021';
    END IF;
    IF parent_hash IS NULL OR NEW.signed_doc_hash IS DISTINCT FROM parent_hash THEN
        RAISE EXCEPTION 'signed_doc_hash does not match canonical_hash of disclosure %', NEW.disclosure_id
            USING ERRCODE = 'GD022';
    END IF;
    IF parent_pdf IS NULL OR NEW.signed_pdf_hash IS DISTINCT FROM parent_pdf THEN
        RAISE EXCEPTION 'signed_pdf_hash does not match pdf_hash of disclosure %', NEW.disclosure_id
            USING ERRCODE = 'GD102';
    END IF;
    IF NEW.session_id IS NOT NULL THEN
        SELECT ss.disclosure_id, ss.signer_role, ss.status, ss.signed_doc_hash, ss.signed_pdf_hash INTO s
          FROM sign_session ss
         WHERE ss.tenant_id = NEW.tenant_id AND ss.session_id = NEW.session_id
           FOR UPDATE;
        IF NOT FOUND OR s.disclosure_id <> NEW.disclosure_id OR s.signer_role <> NEW.signer_role OR s.status <> 'OPEN'
            OR s.signed_doc_hash <> NEW.signed_doc_hash OR s.signed_pdf_hash <> NEW.signed_pdf_hash THEN
            RAISE EXCEPTION 'signature of disclosure % must come through that disclosure''s open session for % with the pinned hashes',
                NEW.disclosure_id, NEW.signer_role
                USING ERRCODE = 'GD103';
        END IF;
    END IF;
    SELECT r.body -> 'signerSet', r.body ->> 'managerConfirmMode' INTO signer_set, manager_mode
      FROM rule_version r
     WHERE r.tenant_id = NEW.tenant_id AND r.rule_version_id = parent_rule;
    IF signer_set IS NULL OR NOT (signer_set ? NEW.signer_role OR (NEW.signer_role = 'MANAGER' AND manager_mode = 'OPTIONAL')) THEN
        RAISE EXCEPTION 'role % is not in the signer set of the pinned rule % of disclosure %', NEW.signer_role, parent_rule, NEW.disclosure_id
            USING ERRCODE = 'GD104';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 5. signature_evidence: 서명 증거 객체 1개 = 1행(승인 Q2). 문서 DEK 재사용, AAD = {disclosureId, kind, signatureId, tenantId, v:1}
--    (3B 수용심사 §3-4) — 문서 키를 파기하면 서명 증거도 함께 읽을 수 없다. 행은 append-only이고 보존 기록 두 컬럼만 바뀐다.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE signature_evidence (
    tenant_id                TEXT NOT NULL,
    signature_id             UUID NOT NULL,
    kind                     TEXT NOT NULL,
    disclosure_id            UUID NOT NULL,
    storage_key              TEXT NOT NULL,
    sha256                   TEXT NOT NULL,
    bytes                    BIGINT NOT NULL,
    cipher_sha256            TEXT NOT NULL,
    cipher_bytes             BIGINT NOT NULL,
    key_id                   TEXT NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    retention_applied_at     TIMESTAMPTZ,
    retention_applied_until  DATE,
    PRIMARY KEY (tenant_id, signature_id, kind),
    CONSTRAINT fk_signature_evidence_signature FOREIGN KEY (tenant_id, signature_id) REFERENCES signature (tenant_id, signature_id),
    CONSTRAINT fk_signature_evidence_disclosure FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure (tenant_id, disclosure_id),
    CONSTRAINT fk_signature_evidence_key FOREIGN KEY (tenant_id, key_id) REFERENCES document_key (tenant_id, key_id),
    CONSTRAINT ck_signature_evidence_kind CHECK (kind IN ('STROKES', 'IMAGE', 'SCAN')),
    CONSTRAINT ck_signature_evidence_hashes CHECK (sha256 ~ '^[0-9a-f]{64}$' AND cipher_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_signature_evidence_sizes CHECK (bytes >= 1 AND cipher_bytes = bytes + 29),
    CONSTRAINT ck_signature_evidence_storage_key CHECK (
        storage_key = tenant_id || '/' || disclosure_id::text || '/SIG/' || signature_id::text || '/' || kind || '/' || cipher_sha256),
    CONSTRAINT ck_signature_evidence_retention CHECK ((retention_applied_at IS NULL) = (retention_applied_until IS NULL))
);

CREATE FUNCTION ga_signature_evidence_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    sig_disclosure UUID;
    key_disclosure UUID;
    key_material   BYTEA;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'signature_evidence rows are never removed (%)', TG_OP
            USING ERRCODE = 'GD105';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT s.disclosure_id INTO sig_disclosure
          FROM signature s
         WHERE s.tenant_id = NEW.tenant_id AND s.signature_id = NEW.signature_id;
        IF sig_disclosure IS DISTINCT FROM NEW.disclosure_id THEN
            RAISE EXCEPTION 'signature_evidence % belongs to signature % of another disclosure', NEW.kind, NEW.signature_id
                USING ERRCODE = 'GD105';
        END IF;
        SELECT k.disclosure_id, k.wrapped_dek INTO key_disclosure, key_material
          FROM document_key k
         WHERE k.tenant_id = NEW.tenant_id AND k.key_id = NEW.key_id;
        IF key_disclosure IS DISTINCT FROM NEW.disclosure_id OR key_material IS NULL THEN
            RAISE EXCEPTION 'signature_evidence % of disclosure % must use that disclosure''s live document key', NEW.kind, NEW.disclosure_id
                USING ERRCODE = 'GD105';
        END IF;
        IF NEW.retention_applied_at IS NOT NULL THEN
            RAISE EXCEPTION 'signature_evidence % is recorded before its lock is applied', NEW.kind
                USING ERRCODE = 'GD105';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: 보존 기록만 — 첫 적용 시각은 1회, 적용 기한은 증가만(연장만, 3B 승인 Q6)
    IF (to_jsonb(NEW) - ARRAY['retention_applied_at', 'retention_applied_until'])
           IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['retention_applied_at', 'retention_applied_until'])
        OR (OLD.retention_applied_at IS NOT NULL AND NEW.retention_applied_at IS DISTINCT FROM OLD.retention_applied_at)
        OR NEW.retention_applied_until IS NULL
        OR (OLD.retention_applied_until IS NOT NULL AND NEW.retention_applied_until <= OLD.retention_applied_until) THEN
        RAISE EXCEPTION 'signature_evidence % of signature % changes only by recording a longer lock', OLD.kind, OLD.signature_id
            USING ERRCODE = 'GD105';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_signature_evidence_guard
    BEFORE INSERT OR UPDATE OR DELETE ON signature_evidence
    FOR EACH ROW EXECUTE FUNCTION ga_signature_evidence_guard();
CREATE TRIGGER trg_signature_evidence_no_truncate
    BEFORE TRUNCATE ON signature_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION ga_signature_evidence_guard();

-- ---------------------------------------------------------------------------------------------
-- 6. document_artifact: 적용 기한(retention_applied_until) — 완료 때 보존기한이 연장되면 다시 건다. 첫 적용 시각은 3B처럼 1회.
--    기존 행은 적용 시각이 있으면 그때의 retention_until로 채운다(3B는 봉인 때 정한 기한으로만 걸었다).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE document_artifact ADD COLUMN retention_applied_until DATE;

ALTER TABLE document_artifact NO FORCE ROW LEVEL SECURITY;
ALTER TABLE disclosure NO FORCE ROW LEVEL SECURITY;
ALTER TABLE document_artifact DISABLE TRIGGER trg_document_artifact_guard;
UPDATE document_artifact a
   SET retention_applied_until = d.retention_until
  FROM disclosure d
 WHERE d.tenant_id = a.tenant_id AND d.disclosure_id = a.disclosure_id AND a.retention_applied_at IS NOT NULL;
ALTER TABLE document_artifact ENABLE TRIGGER trg_document_artifact_guard;
ALTER TABLE disclosure FORCE ROW LEVEL SECURITY;
ALTER TABLE document_artifact FORCE ROW LEVEL SECURITY;

ALTER TABLE document_artifact
    ADD CONSTRAINT ck_document_artifact_retention CHECK ((retention_applied_at IS NULL) = (retention_applied_until IS NULL));

CREATE OR REPLACE FUNCTION ga_document_artifact_guard() RETURNS trigger
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
        IF NEW.retention_applied_at IS NOT NULL THEN
            RAISE EXCEPTION 'document_artifact % is recorded before its lock is applied', NEW.kind
                USING ERRCODE = 'GD093';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: 보존 기록만 — 첫 적용 시각은 1회, 적용 기한은 증가만
    IF (to_jsonb(NEW) - ARRAY['retention_applied_at', 'retention_applied_until'])
           IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['retention_applied_at', 'retention_applied_until'])
        OR (OLD.retention_applied_at IS NOT NULL AND NEW.retention_applied_at IS DISTINCT FROM OLD.retention_applied_at)
        OR NEW.retention_applied_until IS NULL
        OR (OLD.retention_applied_until IS NOT NULL AND NEW.retention_applied_until <= OLD.retention_applied_until) THEN
        RAISE EXCEPTION 'document_artifact % of disclosure % changes only by recording a longer lock', OLD.kind, OLD.disclosure_id
            USING ERRCODE = 'GD093';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 7. 아웃박스(설계서 §4.5, 승인 Q13 — 8개 이벤트 전부 상태 변경과 같은 트랜잭션). 계약 envelope의 seq는 테넌트 내 갭 없는 단조 정수다.
--    이벤트 INSERT(seq = 머리 + 1) → 머리 이동(+1, 실재 이벤트를 가리킨다). published_at은 피드(Phase 6)가 NULL→값 1회.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE outbox_head (
    tenant_id  TEXT PRIMARY KEY REFERENCES tenant (tenant_id),
    seq        BIGINT NOT NULL,
    CONSTRAINT ck_outbox_head_seq CHECK (seq >= 1)
);

CREATE TABLE outbox_event (
    tenant_id       TEXT NOT NULL REFERENCES tenant (tenant_id),
    seq             BIGINT NOT NULL,
    event_id        UUID NOT NULL,
    type            TEXT NOT NULL,
    version         INT NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    aggregate_kind  TEXT NOT NULL,
    aggregate_id    TEXT NOT NULL,
    payload         JSONB NOT NULL,
    published_at    TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, seq),
    CONSTRAINT uq_outbox_event_id UNIQUE (tenant_id, event_id),
    CONSTRAINT ck_outbox_event_seq CHECK (seq >= 1),
    CONSTRAINT ck_outbox_event_type CHECK (type IN ('DisclosureCreated', 'DisclosureSealed', 'SignatureCaptured', 'DisclosureCompleted',
                                                    'DisclosureVoided', 'DisclosureSuperseded', 'PolicyLinked', 'ComplianceFlagRaised')),
    CONSTRAINT ck_outbox_event_version CHECK (version >= 1),
    CONSTRAINT ck_outbox_event_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE FUNCTION ga_outbox_event_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    head BIGINT;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'outbox_event rows are never removed (%)', TG_OP
            USING ERRCODE = 'GD106';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT h.seq INTO head FROM outbox_head h WHERE h.tenant_id = NEW.tenant_id FOR UPDATE;
        IF NEW.seq <> coalesce(head, 0) + 1 THEN
            RAISE EXCEPTION 'outbox_event seq % of % must follow the head %', NEW.seq, NEW.tenant_id, coalesce(head, 0)
                USING ERRCODE = 'GD106', HINT = 'the feed is gapless: lock outbox_head, insert head + 1, then advance the head';
        END IF;
        IF NEW.published_at IS NOT NULL THEN
            RAISE EXCEPTION 'outbox_event % is written unpublished', NEW.event_id
                USING ERRCODE = 'GD106';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.published_at IS NOT NULL OR NEW.published_at IS NULL
        OR (to_jsonb(NEW) - 'published_at') IS DISTINCT FROM (to_jsonb(OLD) - 'published_at') THEN
        RAISE EXCEPTION 'outbox_event % changes only by recording publication once', OLD.event_id
            USING ERRCODE = 'GD106';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_outbox_event_guard
    BEFORE INSERT OR UPDATE OR DELETE ON outbox_event
    FOR EACH ROW EXECUTE FUNCTION ga_outbox_event_guard();
CREATE TRIGGER trg_outbox_event_no_truncate
    BEFORE TRUNCATE ON outbox_event
    FOR EACH STATEMENT EXECUTE FUNCTION ga_outbox_event_guard();

CREATE FUNCTION ga_outbox_head_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'outbox_head rows are never removed (%)', TG_OP
            USING ERRCODE = 'GD106';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.seq <> 1 THEN
        RAISE EXCEPTION 'outbox_head % starts at 1 (got %)', NEW.tenant_id, NEW.seq
            USING ERRCODE = 'GD106';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.seq <> OLD.seq + 1) THEN
        RAISE EXCEPTION 'outbox_head % advances by exactly one (% -> %)', OLD.tenant_id, OLD.seq, NEW.seq
            USING ERRCODE = 'GD106';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM outbox_event e WHERE e.tenant_id = NEW.tenant_id AND e.seq = NEW.seq) THEN
        RAISE EXCEPTION 'outbox_head % (%) does not point at an event', NEW.tenant_id, NEW.seq
            USING ERRCODE = 'GD106';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_outbox_head_guard
    BEFORE INSERT OR UPDATE OR DELETE ON outbox_head
    FOR EACH ROW EXECUTE FUNCTION ga_outbox_head_guard();
CREATE TRIGGER trg_outbox_head_no_truncate
    BEFORE TRUNCATE ON outbox_head
    FOR EACH STATEMENT EXECUTE FUNCTION ga_outbox_head_guard();

-- ---------------------------------------------------------------------------------------------
-- 8. RLS(V2와 같은 정책)·권한 — 필요한 동사만
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['signature_evidence', 'outbox_head', 'outbox_event']
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

-- 서명은 삽입만, 세션은 삽입·갱신(행 트리거가 허용 범위를 정한다), 증거·산출물은 삽입과 보존 기록만, 아웃박스는 적재와 발행 기록만
REVOKE UPDATE, DELETE ON TABLE signature FROM disclosure_app;
REVOKE DELETE ON TABLE sign_session FROM disclosure_app;
GRANT SELECT, INSERT ON TABLE signature_evidence TO disclosure_app;
GRANT UPDATE (retention_applied_at, retention_applied_until) ON TABLE signature_evidence TO disclosure_app;
GRANT UPDATE (retention_applied_until) ON TABLE document_artifact TO disclosure_app;
GRANT SELECT, INSERT, UPDATE ON TABLE outbox_head TO disclosure_app;
GRANT SELECT, INSERT ON TABLE outbox_event TO disclosure_app;
GRANT UPDATE (published_at) ON TABLE outbox_event TO disclosure_app;

REVOKE ALL ON FUNCTION ga_sign_session_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_signature_evidence_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_outbox_event_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_outbox_head_guard() FROM PUBLIC;
