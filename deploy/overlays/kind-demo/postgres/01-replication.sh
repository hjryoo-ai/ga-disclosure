# kind 데모 PostgreSQL(Phase 8 ⑤): 정기 백업(ga-backup)의 pg_basebackup — 복제 롤 disclosure_backup만, 비밀번호 인증. 초기화 때 한 번(빈 데이터 디렉터리).
echo 'host replication disclosure_backup all scram-sha-256' >> "$PGDATA/pg_hba.conf"
