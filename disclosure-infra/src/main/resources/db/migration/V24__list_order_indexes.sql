-- =============================================================================================
-- V24 — 목록 정렬 인덱스 (Phase 8 계획 ③-x-2, 실측 근거: ./gradlew :disclosure-infra:queryPlanReport)
--
-- 허구 데이터(테넌트 하나에 확인서 10만·플래그 3만·감사 10만, 다른 테넌트 확인서 2만)에서 화면·API의 실제 질의를 EXPLAIN (ANALYZE)한 결과, 순차 스캔 +
-- 정렬이 지배한 것은 두 목록뿐이었다(감사 대상 조회·순번 묶음·게이트 집계·설계사 본인 목록은 기존 인덱스로 닿는다).
--   * 확인서 목록(DisclosureRepository#page — ORDER BY consult_date DESC, disclosure_id DESC, 커서도 같은 열): 준법 첫 쪽·상태 거르기·조직 아래·깊은 쪽
--   * 플래그 큐(ComplianceFlagRepository#page — ORDER BY raised_at DESC, flag_id DESC): 준법 열림·전체·관리자 조직 아래
-- 인덱스는 정렬 키 그대로(테넌트 선두 — RLS 술어와 같다). 거르기(상태·열림·유형·조직)는 인덱스 순서로 읽으며 버린다 — 쪽 크기가 작아 일찍 멈춘다.
-- 전후 표는 Phase 8 보고서. 운영 규모의 큰 표라면 CREATE INDEX CONCURRENTLY(트랜잭션 밖)를 고려할 일이다 — 여기서는 마이그레이션 Job이 앱보다 먼저
-- 돌고(앱은 스키마 버전 가드) 표가 작아 한 트랜잭션의 짧은 쓰기 잠금을 택했다.
-- =============================================================================================

CREATE INDEX ix_disclosure_list_order ON disclosure (tenant_id, consult_date DESC, disclosure_id DESC);
CREATE INDEX ix_compliance_flag_list_order ON compliance_flag (tenant_id, raised_at DESC, flag_id DESC);
