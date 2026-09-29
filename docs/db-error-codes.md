# DB 오류 코드 (SQLSTATE `GDxxx`)

DB 트리거가 불변식 위반을 거부할 때 올리는 사용자 정의 SQLSTATE 목록. 애플리케이션·테스트는 메시지가 아니라 이 코드로 사유를 구분한다. 번호는 한 번 배정하면 재사용하지 않는다.

| 코드 | 마이그레이션 | 대상 | 거부 조건 |
|---|---|---|---|
| GD001 | V3 | `disclosure` UPDATE | 봉인 이후(가변 상태 밖) 본문 컬럼 변경 — 메타(`status`, `superseded_by_id`, `completed_at`, `voided_at`, `void_reason`, `policy_no`, `contract_date`, `retention_until`) 외 전부 |
| GD002 | V3 | `disclosure` DELETE | 항상 |
| GD003 | V3 | `disclosure` UPDATE | 봉인 이후 상태 → 가변 상태(`DRAFT`~`REASONED`) 회귀 |
| GD004 | V3 | `disclosure` UPDATE | `superseded_by_id` 재기록 |
| GD010 | V3 | `disclosure_item`·`recommendation` | 봉인된 부모의 자식 INSERT·UPDATE·DELETE |
| GD011 | V3 | `disclosure_item`·`recommendation` | 부모 확인서 없음 |
| GD020 | V3 | `signature` INSERT | 서명 대상 확인서 없음 |
| GD021 | V3 | `signature` INSERT | 부모 상태가 `SEALED`·`PARTIALLY_SIGNED`가 아님 |
| GD022 | V3 | `signature` INSERT | `signed_doc_hash ≠ disclosure.canonical_hash` |
| GD030 | V3 | append-only 테이블(`signature`, `audit_log`, `document_artifact`, `audit_anchor`)·`disclosure`·자식 테이블 | UPDATE·DELETE(append-only 테이블), TRUNCATE |
| GD040 | V4 | `rule_version` INSERT | GLOBAL인데 `status ≠ APPROVED`, 또는 TENANT인데 `status ≠ DRAFT` |
| GD041 | V4 | `rule_version` UPDATE | 식별자·`scope` 변경, 또는 TENANT·DRAFT가 아닌 행에서 메타(`status`, `apply_to`, `approved_by`, `approved_at`) 외 컬럼 변경 |
| GD042 | V4 | `rule_version` UPDATE | `apply_to` 재기록(값→다른 값, 값→NULL) |
| GD043 | V4 | `rule_version` UPDATE | `status`가 `DRAFT→APPROVED→ACTIVE→RETIRED` 한 단계 전진이 아님 |
| GD044 | V4 | `rule_version` UPDATE | `approved_by`·`approved_at`을 DRAFT→APPROVED 전이 밖에서 변경 |
| GD045 | V4 | `rule_version`·`form_template` | DELETE·TRUNCATE |
| GD050 | V4 | `form_template` UPDATE | 번들 출처 행의 `apply_to` 외 컬럼 변경 |
| GD051 | V4 | `form_template` UPDATE | 테넌트 작성본을 `apply_from ≤ 오늘(Asia/Seoul)` 이후 변경(`apply_to` 외) |
| GD052 | V4 | `form_template` UPDATE | `apply_to` 재기록(값→다른 값, 값→NULL) |

제약 위반(트리거가 아닌 DB 제약)은 PostgreSQL 표준 코드를 그대로 쓴다: 배타 제약 `23P01`(`ex_rule_version_in_force_overlap`, `ex_form_template_overlap`), CHECK `23514`, PK `23505`.
