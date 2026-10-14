# 질의 계획 실측 (Phase 8 ③-x-2)

- 환경: PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on aarch64-unknown-linux-gnu; Mac OS X aarch64; Java 25.0.4.1; 14 CPUs
- 데이터: {"disclosure":120000,"compliance_flag":35000,"audit_log":100000} — 테넌트 PERF1(확인서 10만·플래그 3만·감사 10만, 감사는 실제 경로 34초), 잡음 테넌트 PERF2(확인서 2만·플래그 5천)
- 전: V23, 후: V24 — 같은 데이터, 각 7회 실행 중 뒤 5회의 중앙값(ms)

| 질의 | 출처 | 전 ms | 전 계획 | 후 ms | 후 계획 |
|---|---|---:|---|---:|---|
| D1 확인서 목록 — 준법(테넌트 전체), 첫 쪽 | `DisclosureRepository#page` | 13.91 | Limit → Gather Merge → Sort → Result [100000 rows] → Seq Scan [110000 rows] | 0.13 | Limit → Result → Index Scan(ix_disclosure_list_order) |
| D2 확인서 목록 — 준법, 상태 REASONED | `DisclosureRepository#page` | 7.95 | Limit → Gather Merge → Sort → Result [24882 rows] → Seq Scan [72441 rows] | 0.34 | Limit → Result → Index Scan(ix_disclosure_list_order) |
| D3 확인서 목록 — 설계사 본인 | `DisclosureRepository#page` | 0.72 | Limit → Sort → Result → Index Scan(ix_disc_agent) | 0.72 | Limit → Sort → Result → Index Scan(ix_disc_agent) |
| D4 확인서 목록 — 관리자 조직 아래 | `DisclosureRepository#page` | 10.76 | Limit → Sort → Result [4967 rows] → Seq Scan [120000 rows] | 0.82 | Limit → Result → Index Scan(ix_disclosure_list_order) [1078 rows] |
| D5 확인서 목록 — 준법, 커서 깊은 쪽 | `DisclosureRepository#page` | 8.94 | Limit → Gather Merge → Sort → Result [32136 rows] → Seq Scan [76068 rows] | 0.14 | Limit → Result → Index Scan(ix_disclosure_list_order) |
| F1 플래그 큐 — 준법, 열림 | `ComplianceFlagRepository#page` | 20.55 | Limit → Sort → Result [7513 rows] → Hash Join [7513 rows] → Result [100000 rows] → Seq Scan [120000 rows] → Hash [7513 rows] → Seq Scan [35000 rows] | 0.51 | Limit → Result → Nested Loop → Index Scan(ix_compliance_flag_list_order) → Result → Index Scan(disclosure_pkey) |
| F2 플래그 큐 — 준법, 열림·유형 | `ComplianceFlagRepository#page` | 3.16 | Limit → Sort → Result → Nested Loop → Seq Scan [35000 rows] → Result → Index Scan(disclosure_pkey) | 1.03 | Limit → Result → Nested Loop → Index Scan(ix_compliance_flag_list_order) [2745 rows] → Result → Index Scan(disclosure_pkey) |
| F3 플래그 큐 — 준법, 전체 | `ComplianceFlagRepository#page` | 28.25 | Limit → Sort → Result [30000 rows] → Hash Join [30000 rows] → Result [100000 rows] → Seq Scan [120000 rows] → Hash [30000 rows] → Seq Scan [35000 rows] | 0.36 | Limit → Result → Nested Loop → Index Scan(ix_compliance_flag_list_order) → Result → Index Scan(disclosure_pkey) |
| F4 플래그 큐 — 관리자 조직 아래, 열림 | `ComplianceFlagRepository#page` | 12.49 | Limit → Sort → Result → Hash Join → Seq Scan [35000 rows] → Hash [4967 rows] → Seq Scan [120000 rows] | 2.83 | Limit → Result → Nested Loop → Index Scan(ix_compliance_flag_list_order) [3967 rows] → Index Scan(disclosure_pkey) |
| A1 감사 — 대상 조회 | `AuditLogRepository#readTarget` | 0.03 | Result → Index Scan(ix_audit_log_target) | 0.03 | Result → Index Scan(ix_audit_log_target) |
| A2 감사 — 순번 뒤 한 묶음(검증 작업) | `AuditLogRepository#readAfter` | 0.14 | Limit → Result → Index Scan(audit_log_pkey) | 0.15 | Limit → Result → Index Scan(audit_log_pkey) |
| G1 게이트 한도 집계 | `GateLimitRepository` | 0.03 | Aggregate → Result → Index Only Scan(ix_audit_log_subject_action_at) | 0.04 | Aggregate → Result → Index Only Scan(ix_audit_log_subject_action_at) |
