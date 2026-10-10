-- =============================================================================================
-- V23 — 렌더러 판 고정 (Phase 8 5단계, 8 계획 ⑥·승인 Q9)
--
-- 봉인 PDF를 그린 렌더러 판을 산출물 행에 남긴다. 서명본 덧붙임·재렌더는 이 값으로 한다 — 현재 판으로 다시 그리면 옛 문서의 바이트가 달라져
-- Phase 7 G0 "재렌더 바이트 동일"이 거짓이 된다.
--   * 기존 행(Phase 3B~7 봉인)은 판 1. ADD COLUMN … DEFAULT는 행을 고쳐 쓰지 않는다(카탈로그의 누락값 — 불변 트리거와 무관).
--   * 그 뒤 기본값을 지운다 — 새 INSERT는 판을 반드시 적는다(빠뜨리면 NOT NULL 위반, 조용한 1 없음).
--   * write-once: UPDATE 가드(V8)가 이미 보존 기록 두 컬럼 밖의 모든 컬럼 변경을 거부한다(to_jsonb 비교라 새 컬럼도 포함). 시험이 단언한다.
--   * 한 문서의 산출물(PDF·CANONICAL_JSON·SIGNED_PDF·EVIDENCE_ZIP)은 같은 판이다 — 가드가 INSERT 때 그 문서의 기존 행과 대조(GD093).
-- =============================================================================================

ALTER TABLE document_artifact ADD COLUMN renderer_version SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE document_artifact ALTER COLUMN renderer_version DROP DEFAULT;
ALTER TABLE document_artifact ADD CONSTRAINT ck_document_artifact_renderer_version CHECK (renderer_version >= 1);

CREATE OR REPLACE FUNCTION ga_document_artifact_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_no      TEXT;
    key_disclosure UUID;
    key_material   BYTEA;
    other_version  SMALLINT;
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
        -- (V23) 한 문서의 산출물은 같은 렌더러 판 — 봉인 때 정해진 판을 서명본·증거 패키지가 따른다
        SELECT a.renderer_version INTO other_version
          FROM document_artifact a
         WHERE a.tenant_id = NEW.tenant_id AND a.disclosure_id = NEW.disclosure_id
         LIMIT 1;
        IF other_version IS NOT NULL AND other_version <> NEW.renderer_version THEN
            RAISE EXCEPTION 'document_artifact % of disclosure % must keep the renderer version fixed at sealing', NEW.kind, NEW.disclosure_id
                USING ERRCODE = 'GD093';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: 보존 기록만 — 첫 적용 시각은 1회, 적용 기한은 증가만 (renderer_version을 포함한 그 밖의 컬럼은 불변)
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
REVOKE ALL ON FUNCTION ga_document_artifact_guard() FROM PUBLIC;
