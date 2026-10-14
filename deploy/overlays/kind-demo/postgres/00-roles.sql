-- kind 데모 PostgreSQL 초기화(Phase 8 ③): 롤 비밀번호를 마운트한 Secret 파일에서 psql 변수로 읽고(명령줄·환경변수에 값 없음), compose·하네스와 같은
-- docker/postgres/init-roles.sql을 그대로 실행한다(롤 정의의 출처 하나).
\set migrator_password `cat /var/run/ga-pg/migrator-password`
\set app_password `cat /var/run/ga-pg/app-password`
\set operator_password `cat /var/run/ga-pg/operator-password`
\set job_lock_password `cat /var/run/ga-pg/job-lock-password`
\set health_password `cat /var/run/ga-pg/health-password`
\set backup_password `cat /var/run/ga-pg/backup-password`
\i /ga-init/init-roles.sql
