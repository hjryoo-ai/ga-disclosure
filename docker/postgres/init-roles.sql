-- =============================================================================================
-- init-roles.sql — 클러스터 롤과 DB 소유권. 컨테이너 최초 기동 시 superuser로 1회 실행된다
-- (docker-entrypoint-initdb.d, POSTGRES_DB=disclosure). docker-compose와 Testcontainers 하네스가 같은 파일을 쓴다.
--
-- 자격 증명은 로컬 개발·테스트 전용 값이다. 운영 환경은 비밀 관리자로 주입하고 이 파일을 쓰지 않는다.
-- (PostgresHarness의 상수와 짝을 이룬다.)
-- =============================================================================================

-- 마이그레이션 전용(스키마 소유자). RLS 우회 권한 없음 — FORCE ROW LEVEL SECURITY로 소유자도 정책을 따른다.
CREATE ROLE disclosure_migrator LOGIN PASSWORD 'migrator_local_only'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- 애플리케이션 데이터소스. RLS 우회·롤 전환·객체 생성 불가. 테이블 DML 권한은 V2__rls.sql이 준다.
CREATE ROLE disclosure_app LOGIN PASSWORD 'app_local_only'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

ALTER DATABASE disclosure OWNER TO disclosure_migrator;
REVOKE ALL ON DATABASE disclosure FROM PUBLIC;
GRANT CONNECT ON DATABASE disclosure TO disclosure_app;

ALTER SCHEMA public OWNER TO disclosure_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
