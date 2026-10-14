-- =============================================================================================
-- V22 — 헬스 롤·백업 롤 (Phase 8 2단계, 8 계획 승인 Q1·Q3)
--
--   disclosure_health : DatabaseHealthIndicator·스키마 버전 가드의 풀 없는 단일 연결. SELECT 1과 flyway_schema_history의
--                       (version, success) 컬럼만 읽는다 — 테넌트 표 권한 0. 앱 롤로 하면 테넌트 바인딩 없이 쓰이는 앱 롤 연결이
--                       처음 생긴다(CLAUDE.md 규칙 5 "각 항목은 … 전용 롤").
--   disclosure_backup : 물리 백업(pg_basebackup)의 복제 프로토콜 전용. CONNECT 없음(init-roles.sql) — 일반 세션으로 들어오지 못하고,
--                       들어오더라도 표 권한 0. 논리 백업(pg_dump)은 RLS 때문에 BYPASSRLS가 필요해 쓰지 않는다.
--
-- 두 롤은 클러스터 수준이라 docker/postgres/init-roles.sql이 만든다. 여기서는 존재·속성을 단언하고 헬스 롤의 컬럼 권한을 준 뒤
-- 그 밖의 권한이 0임을 단언한다(V12 작업 잠금 롤과 같은 방식). 시험 V22RolesIT가 같은 단언을 실행 시점에 다시 한다.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 0. 롤 단언
-- ---------------------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'disclosure_health'
                     AND rolcanlogin AND NOT rolbypassrls AND NOT rolsuper AND NOT rolinherit AND NOT rolcreaterole AND NOT rolcreatedb
                     AND NOT rolreplication) THEN
        RAISE EXCEPTION 'V22 needs role disclosure_health (LOGIN, NOINHERIT, NOBYPASSRLS, NOREPLICATION) — see docker/postgres/init-roles.sql';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'disclosure_backup'
                     AND rolcanlogin AND rolreplication AND NOT rolbypassrls AND NOT rolsuper AND NOT rolinherit AND NOT rolcreaterole
                     AND NOT rolcreatedb) THEN
        RAISE EXCEPTION 'V22 needs role disclosure_backup (LOGIN, REPLICATION, NOINHERIT, NOBYPASSRLS) — see docker/postgres/init-roles.sql';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
                WHERE r.rolname IN ('disclosure_health', 'disclosure_backup'))
        OR EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
                    WHERE r.rolname IN ('disclosure_health', 'disclosure_backup')) THEN
        RAISE EXCEPTION 'disclosure_health and disclosure_backup are members of no role and have no members';
    END IF;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 1. 헬스 롤 권한 — 스키마 USAGE와 이력 표 두 컬럼
-- ---------------------------------------------------------------------------------------------
GRANT USAGE ON SCHEMA public TO disclosure_health;
GRANT SELECT (version, success) ON flyway_schema_history TO disclosure_health;

-- ---------------------------------------------------------------------------------------------
-- 2. 권한 0 단언 — 표(이력 표 두 컬럼 외)·정의자 함수·백업 롤의 스키마 접근
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    bad TEXT;
BEGIN
    SELECT string_agg(r.rolname || ':' || c.relname, ', ') INTO bad
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     CROSS JOIN (VALUES ('disclosure_health'), ('disclosure_backup')) AS r(rolname)
     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
       AND has_table_privilege(r.rolname, c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER');
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'health/backup roles must hold no table privilege: %', bad;
    END IF;

    SELECT string_agg(r.rolname || ':' || c.relname || '.' || a.attname, ', ') INTO bad
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
     CROSS JOIN (VALUES ('disclosure_health'), ('disclosure_backup')) AS r(rolname)
     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
       AND has_column_privilege(r.rolname, c.oid, a.attnum, 'SELECT, INSERT, UPDATE, REFERENCES')
       AND NOT (r.rolname = 'disclosure_health' AND c.relname = 'flyway_schema_history' AND a.attname IN ('version', 'success')
                AND NOT has_column_privilege(r.rolname, c.oid, a.attnum, 'INSERT, UPDATE, REFERENCES'));
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'health/backup roles must hold no column privilege beyond flyway_schema_history(version, success): %', bad;
    END IF;

    SELECT string_agg(r.rolname || ':' || p.proname, ', ') INTO bad
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     CROSS JOIN (VALUES ('disclosure_health'), ('disclosure_backup')) AS r(rolname)
     WHERE n.nspname = 'public' AND p.prosecdef AND has_function_privilege(r.rolname, p.oid, 'EXECUTE');
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'health/backup roles must execute no SECURITY DEFINER function: %', bad;
    END IF;

    IF has_schema_privilege('disclosure_backup', 'public', 'USAGE') OR has_schema_privilege('disclosure_backup', 'public', 'CREATE')
        OR has_schema_privilege('disclosure_health', 'public', 'CREATE')
        OR has_database_privilege('disclosure_backup', current_database(), 'CONNECT') THEN
        RAISE EXCEPTION 'disclosure_backup holds no schema or database privilege; disclosure_health cannot create';
    END IF;
END
$$;
