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

-- 운영자 CLI의 테넌트 목록 조회 전용(Phase 1). tenant.tenant_id 컬럼 SELECT와 전용 정책(V4)만 가진다.
CREATE ROLE disclosure_operator LOGIN PASSWORD 'operator_local_only'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

-- 파기(Phase 5 V9, 승인 Q2). 파기자 롤은 파기 함수 3개의 EXECUTE만 받는다(V9). 앱 롤은 이 롤로 SET ROLE만 할 수 있고(트랜잭션 안
-- SET LOCAL ROLE), 권한을 물려받지 않는다. 정의자 롤은 그 함수들의 소유자(SECURITY DEFINER)이며 테이블 소유자가 아니다 — 지정 컬럼의
-- 갱신과 판정 읽기만 받고 RLS를 그대로 따른다. 마이그레이터는 함수 소유권을 넘기기 위해 정의자 롤로 SET ROLE할 수 있다.
CREATE ROLE disclosure_destroyer NOLOGIN
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
CREATE ROLE disclosure_destroy_definer NOLOGIN
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
GRANT disclosure_destroyer TO disclosure_app WITH INHERIT FALSE, SET TRUE;
GRANT disclosure_destroy_definer TO disclosure_migrator WITH INHERIT FALSE, SET TRUE;

ALTER DATABASE disclosure OWNER TO disclosure_migrator;
REVOKE ALL ON DATABASE disclosure FROM PUBLIC;
GRANT CONNECT ON DATABASE disclosure TO disclosure_app;
GRANT CONNECT ON DATABASE disclosure TO disclosure_operator;

ALTER SCHEMA public OWNER TO disclosure_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
