-- =============================================================================================
-- V2__rls.sql — 테넌트 격리 2차 방어(PostgreSQL RLS)와 애플리케이션 롤 권한
--
-- 롤(클러스터 수준, docker/postgres/init-roles.sql에서 생성):
--   disclosure_migrator : 스키마 소유자. Flyway 전용. NOBYPASSRLS — FORCE RLS로 소유자도 정책을 따른다.
--   disclosure_app      : 애플리케이션 데이터소스. NOBYPASSRLS, 테이블 DML(SELECT/INSERT/UPDATE/DELETE)만.
--                         TRUNCATE·REFERENCES·TRIGGER 권한 없음, 스키마 CREATE 권한 없음, 소유자 아님
--                         → RLS 해제·트리거 비활성화·정책 삭제 불가.
--
-- 정책: tenant_id = current_setting('app.tenant_id', true)
--   미설정이면 NULL(또는 트랜잭션 종료 후 빈 문자열) → 어떤 행과도 같지 않음 → 0행.
--   WITH CHECK도 같은 식 → 다른 테넌트 tenant_id로 INSERT/UPDATE 불가.
--   값은 TenantSessionBinder가 트랜잭션마다 set_config('app.tenant_id', ?, true)로 넣는다.
-- =============================================================================================

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'tenant', 'identity_link', 'rule_version', 'form_template',
        'product_group', 'product_catalog', 'insurer_panel', 'customer_ref',
        'disclosure', 'disclosure_item', 'recommendation', 'document_artifact',
        'sign_session', 'signature', 'audit_log', 'audit_anchor',
        'subject_policy', 'compliance_flag'
    ]
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

-- 애플리케이션 롤은 스키마를 쓸 수만 있다(객체 생성 불가). Flyway 이력 테이블에는 권한이 없다.
GRANT USAGE ON SCHEMA public TO disclosure_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON TABLE flyway_schema_history FROM disclosure_app;
