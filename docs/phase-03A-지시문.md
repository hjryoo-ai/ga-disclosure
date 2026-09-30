# Phase 3A 지시문 — 워크플로 코어: 애그리게이트·상태기계·검증 배선·스냅샷 (v1.0)

## 역할과 맥락

`ga-disclosure` Phase 2(`phase-2`) 수용 후. 대상은 설계서 **§6.1(상태기계), §6.2(검증 단계 배선), §6.3(등급·순위 표기·정합성), §5(`disclosure`·`disclosure_item`·`recommendation`, V6), §4.1(엔진 클라이언트), §12 Phase 3A**. 봉인(정규화·채번·PDF·스토리지·체인)과 정정·무효는 **3B**다. 이 Phase가 끝나면 확인서 한 건이 `DRAFT`에서 `REASONED`까지 규칙대로 움직이고, 어떤 순서의 명령에도 §6.1 밖의 전이가 일어나지 않으며, 항목이 바뀌면 스냅샷이 반드시 무효화된다는 것이 테스트로 증명돼야 한다.

## 시작 전 보고

계획에 ① 선행 소과제 A~D 처리 ② 애그리게이트 경계(무엇이 `Disclosure` 안에 있고 무엇이 밖에 있는지) ③ 엔진 클라이언트의 테스트 대역 방식(계약 스키마로 응답을 검증하는 페이크) ④ V6 DDL 초안 ⑤ PDF 변환기 후보 비교 결과(3B용)를 넣고 승인 후 진행.

## 선행 소과제

- **A. 계약 PR.** 상품 키 규칙(40자·패턴), E3 보고서의 추가형 변경(있으면), `grade_source` 관련 문서 반영. `ga-disclosure` main 병합 후 엔진 UPSTREAM 갱신은 엔진 쪽 후속.
- **B. DEK 최초 생성 멱등화**(Phase 2 D3). 동시 등록 50건 테스트에서 실패 0.
- **C. 설계서 v1.7·CLAUDE.md 반영**(`phase-02-수용심사.md` §4).
- **D. PDF/A 변환기 후보 비교**(3B 선행). 조건: HTML→PDF/A-2b 이상, 한글 폰트 임베드, 생성일·문서 ID 등 메타데이터 고정으로 **같은 입력 → 같은 바이트**, Boot BOM·라이선스 충돌 없음. 후보별로 같은 HTML을 2회 변환해 바이트 비교한 결과를 표로. 선택은 3B 지시문에서 확정한다.

## 작업 목록

### 1. V6 마이그레이션
- `disclosure`: `grading_policy_version_id`, `ranking_policy_version_id`, `tie_break`, `grade_basis JSONB`, `snapshot_generated_at`(설계서 §5). 전부 본문 컬럼 — Phase 0 메타 허용 목록 방식으로 자동 불변임을 C7 매트릭스에 편입해 확인.
- `disclosure_item`: `tie BOOLEAN`, `unavailable_reason TEXT`, `grade_source TEXT`(ENGINE|LOCAL). CHECK: `grade_status='OK'` ⇒ `tie NOT NULL AND unavailable_reason IS NULL AND grade_source='ENGINE'`; `'UNAVAILABLE'` ⇒ `tie IS NULL AND unavailable_reason NOT NULL`; `temp_product` ⇒ `grade_source='LOCAL' AND unavailable_reason='TEMP_PRODUCT'`.
- `status` CHECK를 열거 10종으로. `disclosure_no`·`canonical_hash` 등 봉인 컬럼은 3B까지 NULL.

### 2. 애그리게이트 (`disclosure-domain` + `disclosure-workflow`)
- `Disclosure` 애그리게이트: 헤더·항목·추천사유·스냅샷 요약을 한 단위로. 상태 전이는 애그리게이트 메서드로만(`compare()`, `applySnapshot()`, `reason()`, 3B에서 `seal()`). 각 메서드는 설계서 §6.1 전이표의 조건을 검사하고 위반이면 `IllegalTransition(from, command)` 예외. 상태별 허용 명령 표를 **데이터 구조(EnumMap)** 로 두고 테스트가 그 표와 §6.1을 대조한다.
- 항목 변경(`replaceItems`)은 `DRAFT`·`COMPARED`·`GRADED`·`REASONED`에서만 가능하고, `GRADED` 이후면 스냅샷을 폐기하고 `COMPARED`로 되돌린다(§6.1). 추천사유도 스냅샷과 함께 폐기(항목 집합이 바뀌면 사유의 대상이 바뀐다).
- 임시등록 항목: `tempProduct=true`면 `quoteDocNo` 필수(값객체 생성 시 검증), 엔진 요청에서 제외, 스냅샷 적용 시 로컬 `UNAVAILABLE(TEMP_PRODUCT)`로 채움.
- 애그리게이트는 `ValidationSubject`를 구현한다(Phase 1 인터페이스). `isInsurerOnPanel`은 생성 시 주입된 함수(Phase 2 포트 어댑터).
- `RegisterCustomer` 유스케이스(Phase 2 `CustomerRefService` 위에): 입력은 `Sensitive` 값객체, 출력은 `CustomerRef`. 데모 시드가 파일에서 읽어 호출.

### 3. 유스케이스 (`disclosure-workflow`)
- `CreateDraft(tenant, agent, customerRef, groupCode, consultDate, templateType)` → 룰·템플릿 해석(Phase 1 `RuleResolver`·`TemplateResolver`, 기준일 = 상담일)을 **초안 생성 시점에 한 번** 하고 두 버전 ID를 헤더에 고정한다. 봉인까지 상담일이 바뀌지 않으므로 재해석하지 않는다(상담일 변경은 허용하지 않는 명령이다 — 새 초안을 만든다).
- `ReplaceItems`, `RequestGrades`, `SetRecommendations`, `Validate(stage)`. 각 유스케이스는 하나의 트랜잭션이며 상태 전이·검증 결과(실패 포함)·엔진 호출을 `audit_log`에 같은 트랜잭션으로 남긴다(Phase 1 `AuditPort`).
- `Validate(stage)`는 Phase 1 실행기를 그 단계로 호출하고 결과 목록을 돌려준다. `COMPARE`·`GRADE`·`REASON` 전이는 해당 단계 검증 전건 통과가 조건. `overridable=true` 규칙의 실패는 **관리자 예외 승인 기록**(`review`, 3B에서 봉인 조건으로 소비)이 있어야 전이 가능 — 이번 Phase는 `review` 테이블(V6에 추가: `review_id, disclosure_id, rule_id, approved_by, approved_at, reason`)과 기록 유스케이스 `ApproveException`만 두고 인가는 Phase 6.

### 4. 엔진 클라이언트 (`disclosure-infra`)
- `GradeSnapshotPort.request(tenant, asOf, groupCode, products[])`, `refetch(snapshotId)`. HTTP 어댑터는 서비스 토큰, 타임아웃·재시도 파라미터(멱등 POST가 아니므로 재시도는 연결 실패에만, 응답 수신 후 실패는 재시도 금지).
- 응답은 **수신 즉시 계약 스키마로 검증**하고(oneOf 분기 포함) 실패하면 스냅샷을 만들지 않는다. 그 다음 `GradeConsistencyCheck`(Phase 1) 실행. 둘 중 하나라도 실패하면 `compliance_flag(GRADE_INCONSISTENT)` + 명시 오류, 상태는 `COMPARED` 유지.
- `ratioToAvg`는 `RatioLabel`로만 흐른다. 아키텍처 규칙(Phase 1 허용 목록)이 그대로 적용된다.
- 테스트 대역: 계약 스키마를 읽어 응답을 검증하는 `FakeEngine`(테스트 픽스처). 데모 모드의 엔진 스텁(부록 B)도 이 페이크를 재사용하되 결정론적 고정 비율표에서 등급을 낸다. 스냅샷 노후(`snapshotMaxAgeDays`)는 봉인 조건이므로 3B에서 검사하되, `snapshot_generated_at`은 지금 저장.

### 5. CLI·데모
- 데모 시드에 확인서 흐름 추가: 고객 파일 등록 → 초안 → 항목 3건 → 산출 → 사유 → `REASONED`. 임시등록 1건 포함 케이스와 고객 요청 보험사 추가 케이스(재산출) 포함. 2회 실행 시 2회째는 새 확인서를 만들지 않는다(고객·상담일·상품군 동일이면 NOOP — 데모 편의 규칙이며 운영 동작이 아님을 주석으로).

## 완료 기준 (전부 테스트로 증명)

| # | 기준 | 증명 방법 |
|---|---|---|
| W1 | 상태 전이표가 §6.1과 동일(허용 명령 × 상태 10종 전수), 표 밖 전이는 `IllegalTransition` | `DisclosureStateTableTest` |
| W2 | 시드 고정 무작위 명령 시퀀스 1,000건에서 불변식 유지: 봉인 전 상태에서만 항목 변경, `GRADED` 이후 항목 변경 ⇒ 스냅샷·사유 폐기 + `COMPARED`, 스냅샷 존재 ⇒ 항목 집합 = 스냅샷 집합(임시등록 제외) | `DisclosurePropertyTest`(SeededCases) |
| W3 | 임시등록 항목: `quoteDocNo` 없으면 생성 실패, 엔진 요청에 미포함, 로컬 `UNAVAILABLE(TEMP_PRODUCT, LOCAL)`, 순위 세트 제외 | `TempProductTest` |
| W4 | 엔진 응답 스키마 위반(예: UNAVAILABLE에 `rankInSet` 포함)·순위 비단조·집합 불일치·`tieBreak` 미허용 각각 → 스냅샷 미생성, `GRADE_INCONSISTENT` 플래그, 상태 `COMPARED` | `GradeSnapshotIT` |
| W5 | 각 단계 검증이 룰의 `stages`대로만 실행되고 실패 시 전이 불가; `overridable` 실패는 `review` 기록 후 전이 가능 | `StageValidationIT` |
| W6 | 룰·템플릿 버전이 초안 생성 시 고정되고 이후 룰 데이터가 바뀌어도 그 확인서에는 영향 없음(경계일 2026-12-31/2027-01-01 두 초안) | `RuleFreezeIT` |
| W7 | 모든 전이·검증·엔진 호출이 `audit_log`에 같은 트랜잭션으로 남고, 유스케이스 실패 시 감사 행도 롤백되지 않는 것이 아니라 **실패 사실이 기록**됨(별도 트랜잭션 또는 실패 기록 규약 — 계획에서 정할 것) | `WorkflowAuditIT` |
| W8 | V6 컬럼이 봉인 후 불변(Phase 0 매트릭스 편입), CHECK 제약 위반 조합 전부 거부 | `ImmutabilityTriggerIT`·`SnapshotColumnCheckIT` |
| W9 | `RegisterCustomer` + 데모 파일 등록, 평문 유출 스캔(Phase 2 P4) 무손상 | `PlaintextLeakScanIT` 확장 |
| W10 | 선행 소과제 A~D: 계약 PR 병합, 동시 등록 50건 실패 0, 문서 반영, PDF 후보 비교표 | 각 증거 |
| W11 | Phase 0~2 전체 무손상, BOM·jqwik 검사 | 빌드 로그 |

## 하지 말 것

- 봉인·PDF·스토리지·체인·정정·무효(3B). 서명(Phase 4). REST 컨트롤러·인가(Phase 6). 프론트(Phase 7).
- 애그리게이트 밖에서 상태 컬럼을 바꾸는 저장소 메서드. 엔진 응답을 스키마 검증 없이 저장하는 것.
- 상담일 변경 명령. 검증 실패를 삼키고 전이하는 것.

## 보고 형식

Phase 2와 동일. 추가로 ① 애그리게이트 경계와 그 근거 ② 감사 실패 기록 규약 ③ PDF 후보 비교표 ④ 3B(봉인) 질문.
