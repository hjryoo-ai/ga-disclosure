-- =============================================================================================
-- init-roles.sql — 클러스터 롤과 DB 소유권. 컨테이너 최초 기동 시 superuser로 1회 실행된다
-- (docker-entrypoint-initdb.d, POSTGRES_DB=disclosure). docker-compose와 Testcontainers 하네스가 같은 파일을 쓴다.
--
-- 자격 증명은 로컬 개발·테스트 전용 값이다. 운영 환경은 비밀 관리자로 주입하고 이 파일을 쓰지 않는다.
-- (PostgresHarness의 상수와 짝을 이룬다.)
-- (Phase 8) 비밀번호는 psql 변수로 바꿀 수 있다 — kind-demo는 마운트한 Secret에서 `psql -v app_password=… -f init-roles.sql`로 준다
-- (deploy/overlays/kind-demo/postgres). 주지 않으면 아래 로컬 기본값(compose·하네스). 운영 프로파일은 로컬 기본값으로 기동하지 않는다
-- (ProdStartupGuard — 키 이름만 알린다).
-- =============================================================================================

\if :{?migrator_password} \else \set migrator_password 'migrator_local_only' \endif
\if :{?app_password} \else \set app_password 'app_local_only' \endif
\if :{?operator_password} \else \set operator_password 'operator_local_only' \endif
\if :{?job_lock_password} \else \set job_lock_password 'job_lock_local_only' \endif
\if :{?health_password} \else \set health_password 'health_local_only' \endif
\if :{?backup_password} \else \set backup_password 'backup_local_only' \endif

-- 마이그레이션 전용(스키마 소유자). RLS 우회 권한 없음 — FORCE ROW LEVEL SECURITY로 소유자도 정책을 따른다.
CREATE ROLE disclosure_migrator LOGIN PASSWORD :'migrator_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- 애플리케이션 데이터소스. RLS 우회·롤 전환·객체 생성 불가. 테이블 DML 권한은 V2__rls.sql이 준다.
CREATE ROLE disclosure_app LOGIN PASSWORD :'app_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

-- 운영자 CLI의 테넌트 목록 조회 전용(Phase 1). tenant.tenant_id 컬럼 SELECT와 전용 정책(V4)만 가진다.
CREATE ROLE disclosure_operator LOGIN PASSWORD :'operator_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

-- 파기(Phase 5 V9, 승인 Q2). 파기자 롤은 파기 함수 3개의 EXECUTE만 받는다(V9). 앱 롤은 이 롤로 SET ROLE만 할 수 있고(트랜잭션 안
-- SET LOCAL ROLE), 권한을 물려받지 않는다. 정의자 롤은 그 함수들의 소유자(SECURITY DEFINER)이며 테이블 소유자가 아니다 — 지정 컬럼의
-- 갱신과 판정 읽기만 받고 RLS를 그대로 따른다. 마이그레이터는 함수 소유권을 넘기기 위해 정의자 롤로 SET ROLE할 수 있다.
CREATE ROLE disclosure_destroyer NOLOGIN
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
CREATE ROLE disclosure_destroy_definer NOLOGIN
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
GRANT disclosure_destroyer TO disclosure_app WITH INHERIT FALSE, SET TRUE;

-- 초안 폐기(Phase 6B V14, 계획 Q10). 폐기 함수 ga_draft_abandon의 EXECUTE만 받는다 — 파기자 롤과 같은 방식(앱은 SET LOCAL ROLE만, 권한 비상속).
-- 함수 소유자는 파기와 같은 정의자 롤이다. 기존 개발 볼륨에는 이 줄을 superuser로 한 번 실행한다(docs/DEVELOPMENT.md "업그레이드").
CREATE ROLE disclosure_abandoner NOLOGIN
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
GRANT disclosure_abandoner TO disclosure_app WITH INHERIT FALSE, SET TRUE;

-- 작업 잠금(Phase 6A V12, 승인 Q8). 테넌트·작업 종류별 세션 advisory lock을 작업 동안 쥐는 전용 커넥션의 롤. 테이블·스키마 권한 0 —
-- 실행하는 SQL은 pg_try_advisory_lock·pg_advisory_unlock·pg_locks 조회뿐이다(전부 pg_catalog, 잠금 공간은 롤과 무관한 DB 전체).
CREATE ROLE disclosure_job_lock LOGIN PASSWORD :'job_lock_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
GRANT disclosure_destroy_definer TO disclosure_migrator WITH INHERIT FALSE, SET TRUE;

-- 헬스·스키마 버전 가드(Phase 8 V22, 승인 Q1). CONNECT·스키마 USAGE와 flyway_schema_history의 version·success 컬럼 SELECT만(V22가 주고 단언한다).
-- 테넌트 표 권한 0. 기존 개발 볼륨에는 이 줄과 아래 백업 롤·CONNECT를 superuser로 한 번 실행한다(docs/DEVELOPMENT.md "업그레이드").
CREATE ROLE disclosure_health LOGIN PASSWORD :'health_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;

-- 물리 백업(Phase 8 V22, 승인 Q3). pg_basebackup의 복제 프로토콜만 — CONNECT 없음(일반 세션 거부), 테이블 권한 0(V22 단언).
-- 논리 백업(pg_dump)은 RLS 때문에 BYPASSRLS가 필요해 쓰지 않는다 — 모든 롤 NOBYPASSRLS를 지킨다.
CREATE ROLE disclosure_backup LOGIN PASSWORD :'backup_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE REPLICATION NOBYPASSRLS NOINHERIT;

ALTER DATABASE disclosure OWNER TO disclosure_migrator;
REVOKE ALL ON DATABASE disclosure FROM PUBLIC;
GRANT CONNECT ON DATABASE disclosure TO disclosure_app;
GRANT CONNECT ON DATABASE disclosure TO disclosure_operator;
GRANT CONNECT ON DATABASE disclosure TO disclosure_job_lock;
GRANT CONNECT ON DATABASE disclosure TO disclosure_health;

ALTER SCHEMA public OWNER TO disclosure_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
