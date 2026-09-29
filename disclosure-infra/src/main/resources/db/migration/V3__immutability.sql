-- =============================================================================================
-- V3__immutability.sql — 봉인 불변성·서명 해시 귀속·append-only를 DB가 강제한다(CLAUDE.md 절대 규칙 2·3)
--
-- 가변 상태: DRAFT, COMPARED, GRADED, REASONED (도메인 DisclosureStatus.isMutable()과 같은 집합)
-- 그 밖의 모든 상태(SEALED, PARTIALLY_SIGNED, COMPLETED, VOID, SUPERSEDED, EXPIRED, 그리고 알 수 없는 값)는
-- "봉인 이후"로 취급한다 — 가변 집합을 허용 목록으로 두어 새 상태가 생겨도 기본이 불변이 되게 한다.
--
-- 거부는 전부 SQLSTATE 'GD0xx'(사용자 정의)로 올린다. 애플리케이션·테스트는 이 코드로 사유를 구분한다.
--   GD001 봉인 이후 본문 컬럼 변경          GD002 disclosure DELETE
--   GD003 봉인 이후 → 가변 상태 회귀       GD004 superseded_by_id 재기록
--   GD010 봉인된 부모의 항목·추천사유 변경  GD011 부모 확인서 없음
--   GD020 서명 대상 확인서 없음            GD021 서명 불가 상태        GD022 서명 해시 ≠ canonical_hash
--   GD030 append-only 테이블 UPDATE/DELETE/TRUNCATE
--
-- 트리거 함수는 SECURITY DEFINER가 아니다(호출 롤 권한·RLS 그대로). disclosure_app은 테이블 소유자가 아니므로
-- ALTER TABLE ... DISABLE TRIGGER 불가, session_replication_role 변경 불가(superuser 전용), TRUNCATE 권한 없음.
-- 보존기간 도래 파기는 Phase 5에서 disclosure_migrator 전용 함수로 구현한다 — 지금은 예외 경로가 없다.
-- =============================================================================================

CREATE FUNCTION ga_is_mutable_status(s TEXT) RETURNS BOOLEAN
    LANGUAGE sql IMMUTABLE STRICT
AS $$ SELECT s IN ('DRAFT', 'COMPARED', 'GRADED', 'REASONED') $$;

-- ---------------------------------------------------------------------------------------------
-- disclosure: 본문 불변 + 상태 회귀 금지 + superseded_by_id write-once
-- 메타 컬럼(봉인 이후에도 변경 허용): status, superseded_by_id, completed_at, voided_at, void_reason,
--                                    policy_no, contract_date, retention_until
-- 본문 컬럼 = 메타가 아닌 전부(tenant_id·disclosure_id 포함). 목록을 나열하지 않고 행 전체에서 메타를 뺀 JSON을 비교한다 —
-- 나중에 추가되는 컬럼은 자동으로 본문(불변)이 된다.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION ga_disclosure_guard_update() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    meta CONSTANT TEXT[] := ARRAY['status', 'superseded_by_id', 'completed_at', 'voided_at', 'void_reason',
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
    RETURN NEW;
END
$$;

CREATE FUNCTION ga_disclosure_guard_delete() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'disclosure rows are never deleted (status %); use VOID or SUPERSEDE', OLD.status
        USING ERRCODE = 'GD002';
END
$$;

CREATE TRIGGER trg_disclosure_guard_update
    BEFORE UPDATE ON disclosure
    FOR EACH ROW EXECUTE FUNCTION ga_disclosure_guard_update();

CREATE TRIGGER trg_disclosure_guard_delete
    BEFORE DELETE ON disclosure
    FOR EACH ROW EXECUTE FUNCTION ga_disclosure_guard_delete();

-- ---------------------------------------------------------------------------------------------
-- disclosure_item · recommendation: 부모가 가변 상태일 때만 INSERT/UPDATE/DELETE
-- 부모 행을 FOR SHARE로 잠가 "검사 후 부모가 봉인되는" 경합을 막는다.
-- UPDATE로 부모를 바꾸는 경우 이전 부모와 새 부모를 모두 검사한다.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION ga_require_mutable_parent(p_tenant TEXT, p_disclosure UUID, p_table TEXT) RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_status TEXT;
BEGIN
    SELECT d.status INTO parent_status
      FROM disclosure d
     WHERE d.tenant_id = p_tenant AND d.disclosure_id = p_disclosure
       FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION '% row refers to missing disclosure %', p_table, p_disclosure
            USING ERRCODE = 'GD011';
    END IF;
    IF NOT ga_is_mutable_status(parent_status) THEN
        RAISE EXCEPTION '% of disclosure % is immutable (parent status %)', p_table, p_disclosure, parent_status
            USING ERRCODE = 'GD010';
    END IF;
END
$$;

CREATE FUNCTION ga_child_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM ga_require_mutable_parent(OLD.tenant_id, OLD.disclosure_id, TG_TABLE_NAME);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM ga_require_mutable_parent(NEW.tenant_id, NEW.disclosure_id, TG_TABLE_NAME);
        RETURN NEW;
    END IF;
    RETURN OLD;
END
$$;

CREATE TRIGGER trg_disclosure_item_guard
    BEFORE INSERT OR UPDATE OR DELETE ON disclosure_item
    FOR EACH ROW EXECUTE FUNCTION ga_child_guard();

CREATE TRIGGER trg_recommendation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON recommendation
    FOR EACH ROW EXECUTE FUNCTION ga_child_guard();

-- ---------------------------------------------------------------------------------------------
-- signature: 문서 해시 귀속 + 서명 가능 상태(SEALED, PARTIALLY_SIGNED)에서만 INSERT
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION ga_signature_guard_insert() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_status TEXT;
    parent_hash   TEXT;
BEGIN
    SELECT d.status, d.canonical_hash INTO parent_status, parent_hash
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
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_signature_guard_insert
    BEFORE INSERT ON signature
    FOR EACH ROW EXECUTE FUNCTION ga_signature_guard_insert();

-- ---------------------------------------------------------------------------------------------
-- append-only: signature, audit_log, document_artifact, audit_anchor
-- 행 UPDATE/DELETE와 문장 수준 TRUNCATE(행 트리거를 건너뛰는 경로)를 모두 막는다.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION ga_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % rejected', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'GD030';
END
$$;

CREATE TRIGGER trg_signature_append_only
    BEFORE UPDATE OR DELETE ON signature
    FOR EACH ROW EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_signature_no_truncate
    BEFORE TRUNCATE ON signature
    FOR EACH STATEMENT EXECUTE FUNCTION ga_append_only();

CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION ga_append_only();

CREATE TRIGGER trg_document_artifact_append_only
    BEFORE UPDATE OR DELETE ON document_artifact
    FOR EACH ROW EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_document_artifact_no_truncate
    BEFORE TRUNCATE ON document_artifact
    FOR EACH STATEMENT EXECUTE FUNCTION ga_append_only();

CREATE TRIGGER trg_audit_anchor_append_only
    BEFORE UPDATE OR DELETE ON audit_anchor
    FOR EACH ROW EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_audit_anchor_no_truncate
    BEFORE TRUNCATE ON audit_anchor
    FOR EACH STATEMENT EXECUTE FUNCTION ga_append_only();

-- 확인서 본문 테이블도 TRUNCATE로 행 트리거를 건너뛸 수 없게 한다.
CREATE TRIGGER trg_disclosure_no_truncate
    BEFORE TRUNCATE ON disclosure
    FOR EACH STATEMENT EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_disclosure_item_no_truncate
    BEFORE TRUNCATE ON disclosure_item
    FOR EACH STATEMENT EXECUTE FUNCTION ga_append_only();
CREATE TRIGGER trg_recommendation_no_truncate
    BEFORE TRUNCATE ON recommendation
    FOR EACH STATEMENT EXECUTE FUNCTION ga_append_only();

-- 함수는 PUBLIC 실행 권한이 기본이다. 트리거 전용이므로 명시적으로 막고 필요한 롤만 연다(트리거 실행에는 EXECUTE가 필요 없다).
REVOKE ALL ON FUNCTION ga_is_mutable_status(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_require_mutable_parent(TEXT, UUID, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_disclosure_guard_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_disclosure_guard_delete() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_child_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_signature_guard_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION ga_append_only() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ga_is_mutable_status(TEXT) TO disclosure_app;
GRANT EXECUTE ON FUNCTION ga_require_mutable_parent(TEXT, UUID, TEXT) TO disclosure_app;
