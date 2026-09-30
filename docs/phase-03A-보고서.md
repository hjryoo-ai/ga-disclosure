# Phase 3A 완료 보고 — 워크플로 코어(애그리게이트·상태기계·검증 배선·스냅샷)

작성 2026-09-30 · 대상 지시문 `docs/phase-03A-지시문.md` v1.0 · 계획 `docs/phase-03A-계획.md`(승인 2026-09-30, `docs/phase-03A-계획승인.md` — Q1~Q7 권장안, 보강 B1~B4, 3B PDF 선결정) · 설계서 v1.7 · 브랜치 `work/phase-3A` · PR [#5](https://github.com/hjryoo-ai/ga-disclosure/pull/5)

## 요약

- **확인서 한 건이 DRAFT → COMPARED → GRADED → REASONED까지 규칙대로 움직인다.**
  - 상태 × 명령 표의 정본은 설계서 §6.1의 `state-table` 블록이다. 테스트가 그 블록을 파싱해 코드의 EnumMap(`DisclosureStateTable`)과 100칸을 양방향으로 대조한다.
  - 구현된 명령 4종 × 상태 10종 전수에서 표 밖 전이는 `IllegalTransition`이고, 상태는 바뀌지 않는다.
  - 시드 고정 무작위 명령 시퀀스 1,000건에서 불변식이 매 단계 유지된다.
- **애그리게이트가 검증 조건까지 검사한다.**
  - 명령마다 적용 후 모습(후보)에 그 단계의 룰 검증을 돌린다. 오버라이드 불가 실패가 있으면 아무것도 바꾸지 않고 업무 거부로 끝난다.
  - 오버라이드 가능 실패는 중간 단계에서 플래그·감사만 남긴다(Q2). 승인 확인은 봉인 판정 `SealGate`의 몫이다. 승인은 대상의 JCS 해시에 귀속된다(Q3).
- **엔진 응답은 수신 즉시 계약 스키마로 검증한다.**
  - 스키마·매핑·정합성 중 하나라도 실패하면 스냅샷을 만들지 않고 `GRADE_INCONSISTENT` 플래그를 올리며, 상태는 COMPARED로 남는다.
  - 테스트 대역 `FakeEngine`은 받은 요청과 자기 응답을 둘 다 계약으로 검증한다. 실제 HTTP 어댑터까지 통과시킨다.
  - 재시도는 연결 실패에만 한다(요청 수로 증명).
- **임시등록 상품은 엔진에 가지 않는다.**
  - 상품키가 없고(Q4), 발행번호가 필수다.
  - 로컬 `UNAVAILABLE(TEMP_PRODUCT, LOCAL)`로 채워지고 순위 세트에서 빠진다.
- **룰·서식 버전은 초안 생성 때 고정한다.** 이후 사규 신설이나 서식 새 버전(상담일에 걸침)이 생겨도 기존 초안에는 영향이 없다.
- **감사 실패 기록 규약**
  - 업무 거부는 커밋되고 감사가 남는다.
  - 예외는 롤백된 뒤, 같은 테넌트의 별도 트랜잭션에 `COMMAND_FAILED`(메시지 없음) 1행을 남긴다.
  - 테넌트가 바인딩되지 않은 명령은 감사하지 않는다(B2).
- **선행 소과제 A~D 완료**
  - A: 계약 1.2.0 PR #4, merge commit `9379be9`, CI run `36705564699`.
  - B: DEK 최초 생성 멱등화. 동시 50건 실패 0이며, 수정 전 코드에서는 13/50이 실패했다.
  - C: 설계서 v1.7, CLAUDE.md 규칙 6.
  - D: PDF 후보 비교표. 동봉 폰트·ICC로 다시 측정했다.
- **테스트 9,268건, 실패 0, 스킵 0**(Phase 2: 7,281건). 규칙 테스트 위반 주입은 11건이다. 10건은 바로 잡혔고, 1건(I7, 룰 고정)은 1차에 잡히지 않았다. 테스트를 강화한 뒤 잡혔다(§6).
- **CI**: PR #5 run `36712325175`(pull_request, head `a01a872` — 코드 최종 커밋)을 `gh run view`로 직접 조회했다.
  - `build` success: `scanPlaintextLeaks: 65 result files, 14 forbidden strings, 0 hits`, `BUILD SUCCESSFUL`, `net.jqwik` 의존 검사 통과, C11 발행 아티팩트 소비 빌드 통과.
  - `no-docker` success: Docker 없이 `disclosure-infra` 통합 테스트가 스킵이 아니라 실패했다. 새 IT인 `WorkflowAuditIT` 등도 포함된다.
  - 보고서 커밋이 올린 새 head의 CI는 PR 코멘트로 덧붙인다(문서만 바뀐다).
  - W8 전수 조합 테스트가 V6 초안의 실제 결함 1건을 찾았다(CHECK의 NULL 통과). 병합 전에 고쳤다.
- **데모**: 가상 고객 파일 등록과 확인서 3사례(정상, 임시등록, 고객 요청 보험사 추가 → 재산출)가 REASONED에 도달한다. 두 번째 실행은 전부 NOOP이다.
- **엔진**
  - E3(PR #1)은 심사 승인에 따라 merge commit `98d5e50`으로 병합했다.
  - E3.1 후속 PR #2를 올렸다. head `fe6f9e7`, CI run `36707884896`(빠른·풀·no-docker 전부 success)은 직접 조회했다. 병합은 심사 뒤에 한다.

## 1. 커밋·파일

커밋 목록(`main..work/phase-3A`, 병합 커밋 제외, 보고서 커밋은 이 표 뒤에 붙는다):

| 커밋 | 요약 |
|---|---|
| `8f5064f` | docs: Phase 2 수용 심사, 3A 지시문·계획 |
| `34237e7` | docs: 3A 계획 승인 기록, 계획에 Q1~Q7·B1~B4·3B PDF 선결정 반영 |
| `e476344` | feat(contracts): engine-disclosure 1.2.0(키 규칙, 엔진 E3 요청 1~4) — PR #4, merge `9379be9` |
| `ef35fd3` | fix(infra): 테넌트 첫 DEK 생성 멱등화(선행 B) |
| `7420c3b` | merge: main(계약 1.2.0)을 작업 브랜치에 반영 |
| `187e08c` | feat: V6, 상태표, 오버라이드 대상(Q3·Q4), 고정 ID 로드 |
| `ecbfe0b` | feat(workflow): `Disclosure` 애그리게이트(W1~W3) |
| `f47938f` | feat: 유스케이스·저장소·엔진 클라이언트·FakeEngine·흐름 IT |
| `eee4a6b` | test: W4~W8 통합 테스트, 쓰기 경로 스캔, V6 CHECK NULL 안전화 |
| `aab2725` | feat: RegisterCustomer·데모 확인서·설계서 v1.7·렌더 자산·PDF 하네스 |
| `a01a872` | feat(workflow): 검증 감사에 판정 룰 정체 기록(I7 1차 미검출에 대한 강화) |

주요 추가 파일:

| 영역 | 파일 |
|---|---|
| DB | `disclosure-infra/src/main/resources/db/migration/V6__workflow_core.sql` |
| 도메인 | `domain/disclosure/{DisclosureCommand, DisclosureStateTable, IllegalTransition, ItemDraft, ItemGrade, GradeSource, FieldValue, Recommendation, AgentReason}`, `domain/grade/EngineSnapshot` |
| 룰 | `ValidationResult`(대상·대상 해시), `ValidationSubject.Item`(`Optional<ProductKey>`·`productName`·`label`), `standard/OverrideSubject`, `RuleResolver.load`·`TemplateResolver.load` |
| 워크플로 | `workflow/disclosure/{Disclosure, DisclosureItem, DisclosureContext, StageCheck, TransitionOutcome, EngineRequest, FieldValueView, SealGate, DisclosureService, CommandRunner, …포트}`, `workflow/customer/{RegisterCustomer, RegistrationKey, CustomerFileParser}` |
| 인프라 | `persistence/{DisclosureRepository, ReviewRepository}`, `engine/{EngineContract, EngineTransport, HttpEngineTransport, EngineGradeClient, EngineCredentialPort, EnvironmentEngineCredentials, EngineClientSettings, EngineEndpoints}`, `engine/stub/{TableEngineStub, StubEngineTransport}`, `json/EngineJson`, testFixtures `FakeEngine` |
| 앱·데모 | `config/WorkflowConfiguration`, CLI `customer import`·`demo disclosures`(`DemoDisclosureSeeder`), `disclosure-demo/src/main/resources/{customers.json, demo/disclosures.json, demo/engine-table.json}` |
| 렌더 자산 | `disclosure-seal/src/main/resources/render/{fonts/NanumGothic-*.ttf, fonts/OFL.txt, icc/sRGB-v2-magic.icc, icc/LICENSE-CC0.txt, SOURCES.md}` |
| 검증 | `verification/pdf-candidates/`(독립 빌드, `run.sh`) |

## 2. 선행 소과제

### A. 계약 PR — 완료

- PR [#4](https://github.com/hjryoo-ai/ga-disclosure/pull/4) → merge commit `9379be96d28f02bf5c3f11a8810210106cc66165`. CI run `36705564699`(head `e476344`)은 `build`·`no-docker` 모두 success였다(`gh run view`로 직접 조회).
- `engine-disclosure.openapi.yaml`을 1.2.0으로 올렸다.
  - `ProductKey`: 40자, `^[A-Z0-9][A-Z0-9-]{0,7}:[A-Za-z0-9][A-Za-z0-9._-]{0,30}$`.
  - `InsurerCode`: `^[A-Z0-9][A-Z0-9-]{0,7}$`(Q1). 요청과 응답이 모두 참조한다.
- E3 요청 1~3(추가형): 400 `AS_OF_IN_FUTURE`·`UNKNOWN_PRODUCT_GROUP`, 422 `INVALID_POLICY`, GET 500 `SNAPSHOT_INTEGRITY`.
- E3 요청 4(설명 변경, E3 심사 §3-3): GET 403 = 인가 거부, 다른 테넌트의 스냅샷은 404.
- 임시등록 미전송: `reason` 예시에서 `TEMP_PRODUCT`를 뺐다.
- 카탈로그 파일 스키마도 같은 키 규칙을 쓴다. `ProductKey`·`InsurerCode` 값객체도 같다.
- 수입은 규칙에 맞지 않는 키를 자르지 않고, `/products/{i}/productKey` 위치와 함께 거부한다(`CatalogFileParserTest`).
- 병합 커밋 해시를 엔진 E3.1에 넘겼다. 엔진 UPSTREAM은 `ga-disclosure@9379be96…`이다.

### B. DEK 최초 생성 멱등화 — 완료

- `customer_data_key` INSERT를 `ON CONFLICT (tenant_id) WHERE status = 'ACTIVE' DO NOTHING`으로 바꿨다.
- 0행이면 재조회한다. READ COMMITTED에서는 진 쪽이 유일 인덱스에서 이긴 쪽의 커밋을 기다린 뒤, 다음 문장이 커밋된 행을 읽는다.
- `CustomerEncryptionIT.firstRegistrationsAreIdempotentUnderConcurrency`: 새 테넌트의 첫 등록 50건을 `CountDownLatch`로 동시에 시작했다.
  - 실패 0, `customer_ref` 50행.
  - ACTIVE 키 1개이고 키 행도 1개다. 진 쪽 키는 저장되지 않는다.
  - 50행 모두 같은 키, 감사 `CUSTOMER_REGISTER` 50행.
- `registrationsDuringRotation…`: 순환과 등록 20건이 겹쳐도 실패 0이다.
- 수정 전 코드에서는 같은 테스트가 50건 중 13건 `DuplicateKeyException`으로 실패했다(주입 기록 §6).
- 같은 규약을 등록 멱등 키에도 썼다. `sameRegistrationKeyConcurrentlyYieldsOneCustomer`: 20건 동시 실행 → 행 1개, 참조 1개.

### C. 설계서 v1.7·CLAUDE.md — 완료

- 수용심사 §4의 1~7을 반영했다.
  - §4.1·§4.2: 키 규칙·임시등록 미전송·집합 검사 범위.
  - §5: `grade_source`, `customer_ref` DELETE 주석.
  - §6.4·§9: 성명 포함·암호화 저장·crypto-shredding.
  - §12: 3A/3B 분할.
  - §4·§14: UPSTREAM 규칙.
  - 부록 B: `customers.json`.
  - CLAUDE.md 규칙 6에 PII 전달 경로 문장을 넣었다.
- 계획에서 생긴 변경
  - §5: V6와 초안 고정.
  - §6.1: `state-table`.
  - §6.2: 감사·트랜잭션 표, 오버라이드 처리, 서식 항목값 출처별 판정.
  - §6.4 1항: B1 예외.
  - §6.4 9항: PDF 변환기.
  - §4.1: 엔진 클라이언트.
  - 부록 B: 엔진 스텁 표.
  - `db-error-codes.md`: GD065·GD080.

### D. PDF/A 변환기 후보 비교 — 완료(§5 표, 3B 선결정 openhtmltopdf)

## 3. 애그리게이트 경계와 근거 (보고 형식 추가 ①)

| `Disclosure` 안 | 밖 | 근거 |
|---|---|---|
| 헤더: ID·설계사·`customerRef`·상품군·상담일·고정된 룰·사규·서식 ID·정본 모드·상태 | 룰·서식 **본문** — 유스케이스가 고정 ID로 로드해 `StageCheck`(실행기)와 `DisclosureContext`(서식)로 건넨다 | 룰은 테넌트 공유 데이터이고 확인서는 ID만 박제한다. 애그리게이트는 저장소를 부르지 않는다 |
| 항목(`item_no` 순), 등급 복사본(`ItemGrade` = 엔진 OK·엔진 산출불가·로컬 산출불가) | 카탈로그 조회 — 유스케이스가 상담일 기준 상품명·기본값을 채운 `ItemDraft`를 넘긴다 | 카탈로그는 외부 정본의 캐시다. 설계사는 카탈로그 값을 덮지 못한다(AGENT 출처 항목만 입력) |
| 추천사유(설계사 코드·텍스트 + 룰의 자동 부가 코드) | 고객 개인정보 | 절대 규칙 6·7. 애그리게이트에는 복호화 경로가 없다 |
| 스냅샷 요약(`EngineSnapshot`: 스냅샷·`basis` 정규형·`generatedAt`) | 엔진 호출(`GradeSnapshotPort`) | 스키마·정합성 검증을 마친 스냅샷만 들어온다(W4) |
| 명령: `replaceItems`·`compare`·`applySnapshot`·`setRecommendations`(각각 표 검사 → 후보 → 단계 검증 → 적용 또는 거부) | 예외 승인(`review`), 준법 플래그, 감사 | 승인은 다른 행위자의 사실이고 append-only다. 확인서 행에 넣으면 봉인 불변과 엉킨다. 부수 효과는 유스케이스가 같은 트랜잭션에 기록한다 |

- **배치**: 상태표·명령·`IllegalTransition`·항목·등급·추천 값 타입은 `disclosure-domain`에 있다.
- `Disclosure`는 `disclosure-workflow`에 있다(`ValidationSubject` 구현, Spring 무의존).
- **상태 변경 경로는 하나다.**
  - 상태 필드는 private이고 setter가 없다.
  - 저장소 포트는 `insert`·`loadForUpdate`·`save`뿐이다.
  - `DisclosureWriteScanTest`(archTest)가 전 모듈 운영 소스의 SQL 문자열을 **들어 있는 메서드**와 함께 찾는다. `disclosure`·`disclosure_item`·`recommendation`을 쓰는 문장이 FQN#메서드 허용 목록 3개(`DisclosureRepository#insert`·`#save`·`#writeChildren`) 밖에 있으면 실패하고, 폐기 항목도 실패시킨다.
  - `DisclosureTransitionTest.implementedListCoversEveryCommandMethodOfTheAggregate`는 애그리게이트의 공개 명령 메서드 집합을 고정한다.
- **상담일 변경 명령 없음**: 필드는 final이고, 날짜를 받는 공개 메서드는 `draft`·`restore`·`isInsurerOnPanel`뿐이다(리플렉션 테스트).

## 4. 감사 실패 기록 규약 (보고 형식 추가 ②)

설계서 §6.2 표를 그대로 옮긴다. 구현은 `CommandRunner`이고, 증거는 `WorkflowAuditIT`다.

| 종류 | 예 | 처리 | 증거 |
|---|---|---|---|
| 업무 거부 | 단계 검증의 오버라이드 불가 실패, 엔진 응답 스키마·정합성 위반, 산출 중 항목 변경(`GRADE_STALE`) | 정상 커밋. `DISCLOSURE_VALIDATE`·`GRADE_FETCH`·`DISCLOSURE_REJECT` 감사 + 플래그, 상태 불변, 결과 객체 반환 | `aBusinessRejectionCommitsItsAuditRowsWithoutChangingState`, `GradeSnapshotIT`, `itemsChangedDuringTheEngineCallMakeTheResultStale` |
| 명령 오류 | `IllegalTransition`, 룰 해석 실패, 엔진 연결 실패·오류 응답·타임아웃, 입력 전제 위반, 예기치 않은 오류 | 롤백 뒤 같은 테넌트의 별도 트랜잭션에 `COMMAND_FAILED` 1행(`command`·`exception`·`code`, 메시지 없음) | `aCommandErrorLeavesOnlyCommandFailedInTheSameTenant`, `engineErrorResponseIsACommandFailureWithoutFlag` |
| 감사 뒤 예외 | 업무 감사 INSERT 직후 예외 주입 | 업무 행·업무 감사 행 모두 없음, `COMMAND_FAILED`(code `UNEXPECTED`)만 | `anExceptionAfterTheAuditRowRollsTheBusinessChangeBackAndRecordsTheFailure` |
| 테넌트 미바인딩 | 테넌트 없이 호출 | 감사하지 않는다. 전 테넌트 `COMMAND_FAILED` 수 불변 | `anUnboundTenantIsNeverAudited` |

트랜잭션 해석(Q6): 엔진 호출만 트랜잭션 밖이다. 순서는 읽기 트랜잭션(요청 + 지문) → 호출 → 쓰기 트랜잭션(`FOR UPDATE` + 지문 대조 + 적용 + 감사)이다. 체인은 매 테스트 끝에 `AuditChain.breaks = []`다.

## 5. PDF 후보 비교표 (보고 형식 추가 ③, 선행 D)

재현 방법은 `verification/pdf-candidates/run.sh`다. 같은 XHTML을 후보마다 별도 프로세스로 2회 변환하고, 폰트·ICC는 저장소 동봉 자산만 쓴다. 실측은 2026-09-30, macOS arm64 · Temurin 25 · Docker Desktop.

| 후보 | 라이선스 | 1회 / 2회 SHA-256 | 바이트 | veraPDF PDF/A-2b |
|---|---|---|---|---|
| openhtmltopdf 1.1.87 기본값 | LGPL-2.1 + Apache-2.0 | `3f521977…` / `485b62e1…` | 불일치(XMP 날짜·`/ID`) | 준수 |
| **openhtmltopdf 1.1.87 세 지점 고정** | 〃 | `44e65db602701e16…` ×2 | **일치** | **준수** |
| Flying Saucer 10.5 + OpenPDF 3.0.5 기본값 | LGPL / MPL | `e3a129ef…` / `58ba07d7…` | 불일치 | 비준수(3) |
| Flying Saucer 정보 날짜 고정 | 〃 | `ec0466d9…` / `e9b978d2…` | 불일치(`/ID` 시간 기반) | 비준수(3) |
| WeasyPrint 70.0 | BSD-3 | `81ff571f8499b8fd…` ×2 | 일치(기본값) | 준수 |
| Gotenberg 8 | MIT | `5231e045…` / `aac71766…` | 불일치(폰트 스트림) | 비준수(1) |
| iText 9 pdfHTML | AGPL / 상용 | — | — | 라이선스 충돌로 미측정 |

- 계획 단계의 측정(JDK 내장 ICC)과 비교했을 때 openhtmltopdf 고정 해시가 달라졌다. 동봉 ICC로 바꿨기 때문이다.
- 결론은 같다.
- 3B 결정: openhtmltopdf(승인 §3). 3B 완료 기준은 CI(Linux) = 로컬(macOS) SHA-256 일치다.

## 6. 테스트와 완료 기준

### 테스트 수 (`./gradlew clean build --no-build-cache`, 전부 실행)

| 모듈 / 스위트 | Phase 2 | Phase 3A |
|---|---|---|
| platform-core test | 3,058 | 3,058 |
| platform-canonical test | 1,054 | 1,054 |
| platform-spring test | 13 | 13 |
| disclosure-domain test | 1,056 | 1,084 |
| disclosure-rules test | 1,252 | 1,283 |
| disclosure-workflow test | — | 1,052 |
| disclosure-audit test | 3 | 3 |
| disclosure-app archTest | 36 | 40 |
| disclosure-infra integrationTest | 802 | 1,674 |
| disclosure-app integrationTest | 7 | 7 |
| **합계** | **7,281** | **9,268** (실패 0, 스킵 0) |

`scanPlaintextLeaks: 65 result files, 14 forbidden strings, 0 hits`. 증가분의 큰 몫은 `DisclosurePropertyTest` 1,000건, `SnapshotColumnCheckIT` 768건, `ImmutabilityTriggerIT` V6 편입분이다.

### 완료 기준

| # | 기준 | 증거 |
|---|---|---|
| W1 | 상태 × 명령 표 = §6.1(정본 `state-table`), 표 밖은 `IllegalTransition` | `DisclosureStateTableTest`(domain: 문서 파싱 ↔ EnumMap 100칸 양방향, 표 밖 전수, 파서 음성 테스트), `DisclosureTransitionTest`(workflow: 상태 10 × 구현 명령 4, 구현 명령 목록 고정, 상담일 불변) |
| W2 | 시드 고정 명령 시퀀스 1,000건 불변식 | `DisclosurePropertyTest.invariantsHoldAfterEveryCommand` 1,000건(시드 `0x5EED3A02`, 각 1~30단계, 10%는 봉인 이후 상태에서 시작). 적용·거부·표 밖·잘못된 입력 경로가 실제로 돌았는지 `everyPathWasExercised`가 확인한다(산출 이후 항목 교체 ≥ 50회) |
| W3 | 임시등록: 발행번호 없으면 생성 실패, 엔진 미전송, 로컬 `UNAVAILABLE(TEMP_PRODUCT, LOCAL)`, 순위 세트 제외 | `TempProductTest`(4), `ItemDraftTest.tempProductWithoutAValidQuoteDocumentNumberCannotBeCreated`, `DisclosureFlowIT.tempProductIsGradedLocallyAndNeverSentToTheEngine`(DB 행·`FakeEngine` 요청 검증 0건) |
| W4 | 스키마 위반·비단조·집합 불일치·미허용 tieBreak → 스냅샷 미생성·`GRADE_INCONSISTENT`·COMPARED | `GradeSnapshotIT.invalidEngineResponsesNeverBecomeSnapshots`(UNAVAILABLE+rankInSet·비단조·누락·초과·미허용 정책), `tieBreakOutsideTheRulesAllowanceIsRejectedByTheClient`, 422·타임아웃·연결 재시도·노후 |
| W5(조정) | 단계 검증은 `stages`대로만, 오버라이드 불가 실패는 전이 불가, 오버라이드 가능 실패는 플래그·감사만, SEAL 판정은 대상 해시가 맞는 승인 없이는 막음 | `StageValidationIT`(4) — 대상이 바뀌면 승인 무효, 재산출해도 같은 대상이면 유지, GD080·GD030·42501. `OverrideSubjectTest`(5) |
| W6 | 룰·서식 버전 고정(경계일 2026-12-31/2027-01-01) | `RuleFreezeIT.boundaryDraftsPinTheirOwnVersionsAndIgnoreLaterRuleData`(뒤에 생긴 사규·서식 v2는 새 초안에만 적용), `RuleResolverTest.loadUsesThePinnedIds…` |
| W7 | 감사 같은 트랜잭션, 실패 기록 규약, 미바인딩 미감사 | `WorkflowAuditIT`(5) |
| W8 | V6 컬럼 봉인 후 불변, CHECK 위반 조합 전부 거부 | `ImmutabilityTriggerIT`(V6 헤더 5컬럼 BODY 편입, 자식 V6 컬럼 UPDATE 케이스), `SnapshotColumnCheckIT`(항목 432 + 헤더 320 조합 + 단일 누락 7 + 정체성·열거) |
| W9 | `RegisterCustomer` + 데모 파일 등록, 평문 유출 무손상 | `PlaintextLeakScanIT`(센티널 파일 등록·NOOP·형식 오류 행 + 데모 `customers.json`, 새 테스트 `demoCustomerFileRegistersOnceAndItsNamesStayEncrypted`), `CustomerFileParserTest`, `scanPlaintextLeaks` 65 files 0 hits |
| W10 | 선행 A~D | §2 |
| W11 | Phase 0~2 무손상, BOM·jqwik | Phase 0~2 테스트 전부가 위 9,268건 안에서 통과했다. `allDependencies` 8,210줄에서 `net.jqwik`는 0건이다.

BOM: `main` 대비 락 파일에 **새 좌표와 버전 변경이 모두 0건**이다. `disclosure-infra`의 기존 좌표만 컴파일 클래스패스에 추가됐다.

- `json-schema-validator` 3.0.6: 카탈로그 고정값. BOM 호환 최신판이다.
- `jackson-dataformat-yaml` 3.1.5: BOM.
- `snakeyaml-engine` 3.0.1, `itu` 1.14.0, `slf4j-api` 2.0.18: 기존 전이 의존. |

### 규칙 테스트 위반 주입 기록 (주입 → 실패 확인 → 제거)

| # | 대상(기준) | 주입 | 결과 |
|---|---|---|---|
| I0 | 선행 B | `CustomerVaultRepository.createKey`의 `ON CONFLICT` 없는 수정 전 코드 | `firstRegistrationsAreIdempotentUnderConcurrency` 실패 — 50건 중 13건 `DuplicateKeyException`. 수정 후 5회 반복 21/21 통과 |
| I1 | W1 | 설계서 `state-table`에서 `GRADED,APPLY_SNAPSHOT,GRADED` 줄 삭제 | `designDocumentTableEqualsTheCodeTableCellByCell` 실패 "[GRADED × APPLY_SNAPSHOT] expected: [] but was: [GRADED]". 첫 시도는 설계서가 테스트 입력이 아니라 UP-TO-DATE로 건너뛰어 `disclosure-domain` test inputs에 설계서를 추가했다 |
| I2a | W2 | `replaceItems`가 스냅샷을 버리지 않고 산출 이후 상태 유지 | `DisclosurePropertyTest` case 1·10·18… 실패(애그리게이트 불변식 "item 1 grade does not match the snapshot state") |
| I2b | W2 | I2a + 애그리게이트 자체 불변식 검사 무력화 | 같은 case들이 속성 테스트의 독립 불변식(항목 집합 ≠ 스냅샷 집합)으로 실패 |
| I3 | W4 | `EngineGradeClient`의 응답 스키마 검증 생략 | `invalidEngineResponsesNeverBecomeSnapshots[UNAVAILABLE_WITH_RANK]` 실패 — 정합성 (ii)로만 거부되고 `schema:` 위반이 없다 |
| I4 | 쓰기 경로 | `DisclosureRepository.markCompared`(상태만 바꾸는 `UPDATE disclosure`) 추가 | `DisclosureWriteScanTest.disclosureTablesAreWrittenOnlyByTheAllowedRepositoryMethods` 실패("…#markCompared — UPDATE disclosure") |
| I5 | W3 | `ItemDraft` 발행번호 검사 무력화 | `TempProductTest.quoteDocumentNumberIsRequiredToCreateATempItem` 실패 |
| I6 | W5 | `Review.covers`가 규칙 ID만 비교(대상 해시 무시) | `StageValidationIT.overridableFailuresPass…SealNeedsMatchingApprovals` 실패 — 대상이 바뀐 뒤에도 차단 목록이 비어 있다 |
| I7 | W6 | 명령이 고정 ID 대신 상담일로 룰을 재해석 | **1차 미검출**. 뒤에 생긴 사규가 3A 검증에 관찰되는 키를 바꾸지 않아 판정이 같았다. 테스트를 좁히지 않고 검증 감사에 판정 룰의 정체(고정 ID·사규 ID·본문 해시·서식 버전)를 남기게 한 뒤 `RuleFreezeIT`가 이를 단언하도록 강화했다(`a01a872`). **2차**: 같은 주입 → "뒤에 생긴 사규는 기존 초안의 판정에 쓰이지 않는다" 실패 |
| I8 | W7 | `CommandRunner`가 실패 기록 생략 | `WorkflowAuditIT`의 감사 뒤 예외·명령 오류 2건 실패 |
| I9 | W9 | `CustomerFileParser` 오류 메시지에 전화번호 값 포함 | `PlaintextLeakScanIT.noPlaintextInLogs…` 실패 + 빌드 `scanPlaintextLeaks` 실패(결과 XML에 센티널) |

모든 주입은 제거한 뒤 전체 빌드가 통과했다. 주입과 별개로, 개발 중 규칙 테스트가 **실제 위반 2건**을 잡았다.

- **V6 초안 등급 CHECK가 `ratio_to_avg`를 언급했다.** `RatioLabelRoundTripIT.columnIsTextWithoutFormatCheck`이 잡았다. 규칙을 좁히지 않고 생성 컬럼 `ratio_present`로 코드를 옮겼다.
- **CHECK의 NULL 통과로 OK 행이 `grade_source` 없이 들어갔다.** `SnapshotColumnCheckIT`가 잡았다. `coalesce`·`IS NOT DISTINCT FROM`으로 고쳤다(D5).

### CLAUDE.md 규칙 9 기록

빌드·테스트·데모 로그에서 지시문 형태의 문장은 0건이었다. 엔진 E3.1 포크도 빌드 로그 242행에서 0건을 보고했다.

## 7. 설계서와 달리 구현했거나 해석한 지점

| # | 지점 | 이유 |
|---|---|---|
| D1 | COMPARED 이후의 항목 교체는 교체한 후보로 COMPARE 단계를 다시 통과해야 적용된다(막히면 업무 거부). DRAFT에서는 검증 없이 교체한다 | "COMPARED"가 비교 단계 검증을 통과한 상태라는 뜻을 유지하기 위해서다. 표의 결과(COMPARED)는 그대로다 |
| D2 | 서식 항목값의 **출처별 존재 판정**(`FieldValueView`, 설계서 §6.2 v1.7). CATALOG는 카탈로그 `defaults`의 같은 코드 값이고, 데모 카탈로그 기본값 키를 서식 코드로 바꿨다. ENGINE은 등급 복사본 유무, AGENT는 저장 입력 → 추천사유 → 비추천 판단, SYSTEM은 항상 존재 | 서식이 항목 ↔ 값의 결속을 말하지 않는다(설계서 v1.6까지 미정). AGENT 출처 항목은 추천사유로 채워진 것으로 본다 — `RuleFreezeIT`에서 v2의 `TEST_ONLY_FIELD`(AGENT)가 추천사유만으로 충족되는 한계가 보였다. 결속을 서식 데이터에 두는 것은 3B 질문 1 |
| D3 | 오버라이드 가능 실패의 플래그 유형은 `VALIDATION_OVERRIDE` 하나다. 대상은 `DISCLOSURE_RULE`/`{확인서}/{규칙 ID}` | 규칙 ID → 플래그 유형 대응을 코드에 두지 않기 위해서다. 규칙 ID가 대상에 있다. `TEMP_PRODUCT`·`GRADE_UNAVAILABLE` 큐 유형은 3B 질문 6 |
| D4 | V6가 계획 초안보다 늘었다. `disclosure_item.group_code`(재로드 시 R-SAME-GROUP 판정), `ratio_present` 생성 컬럼, `field_values` 객체 CHECK, 확인서 안 상품키 유일, `review.approved_role`·규칙 ID 형식, GD065 | 구현 중 드러난 필요다. 특히 `group_code`가 없으면 DRAFT의 다른 상품군 항목이 재로드 후 헤더 상품군으로 보인다. `ratio_present`는 `RatioLabelRoundTripIT`(`ratio_to_avg`를 언급하는 CHECK 0건)이 잡은 위반을 규칙을 좁히지 않고 옮긴 결과다 |
| D5 | V6 CHECK를 `coalesce(…, false)`·`IS NOT DISTINCT FROM`으로 썼다 | W8이 찾은 결함이다. CHECK는 식이 NULL이면 통과해 `grade_source` NULL인 OK 행이 들어갔다 |
| D6 | 엔진 오류 응답·연결 실패는 `COMMAND_FAILED`(플래그 없음), 응답을 받았는데 틀리면 `GRADE_INCONSISTENT` | 엔진 장애와 엔진 데이터 모순을 준법 큐에서 구분한다 |
| D7 | `CanonicalValue`(워크플로)가 스칼라를 `[x]`로 감싸 정규화한다 | 플랫폼 정규화기(erdtman JCS)는 최상위가 객체·배열인 문서만 받는다. 발행된 플랫폼 0.1.0을 바꾸지 않았다 |
| D8 | 스냅샷을 DB에서 복원하면 결과 순서는 항목 순서다(엔진 응답 순서가 아니다) | 항목 행에 엔진 결과를 복사해 저장하기 때문이다. 순서에 의미를 두지 않는다(정합성 검사는 순서 무관). 3B canonical의 순서 규약은 질문 3 |
| D9 | 예외 승인은 **지금** SEAL 단계 결과에 같은 규칙·대상 해시의 오버라이드 가능 실패가 있어야 기록된다 | 관리자가 본 적 없는 대상을 승인하지 않기 위해서다. Q3의 귀속 원리와 같다 |
| D10 | 데모 확인서 시더는 `disclosure-app` CLI에 있다(`disclosure-demo`에는 데이터만) | "어떤 모듈도 demo에 의존하지 않는다" 규약 때문이다. 데모 NOOP 규칙은 주석과 부록 B에 "운영 동작 아님"으로 적었다 |
| D11 | 엔진 클라이언트가 **자기 요청도** 계약 요청 스키마로 검증한다 | 계약 밖 요청(41자 키 등)을 보내기 전에 막는다. `FakeEngine`도 받는 쪽에서 검증한다 |
| D12 | 렌더 자산(폰트·ICC)을 3A에서 `disclosure-seal` 리소스에 동봉했다(계획은 하네스 내려받기) | 승인 §3이 동봉을 결정했다. 하네스와 3B 렌더러가 같은 파일을 쓴다 |

## 8. 데모

로컬 `docker compose` PostgreSQL에서 `disclosure-demo/scripts/seed.sh 2026-09-30`을 두 번 실행했다.

```
(1회)
CUSTOMER_IMPORT DEMO1 C01 CREATED ref=CR-7a58…   (DEMO1·DEMO2 × C01~C03, 6건 CREATED)
  A-1 replace -> DRAFT / compare -> COMPARED / grade -> GRADED / reason -> REASONED
  A-2 compare -> COMPARED overridable=[R-TEMP-PRODUCT] / grade -> GRADED overridable=[R-GRADE-UNAVAILABLE] / reason -> REASONED
  A-1-REQUEST … reason -> REASONED / customer-request -> COMPARED / regrade -> GRADED overridable=[R-GRADE-UNAVAILABLE] / reason -> REASONED
(2회)
CUSTOMER_IMPORT … NOOP (6건)
DEMO_DISCLOSURE DEMO1 A-1 NOOP existing=3507764a-…   (A-2·A-1-REQUEST도 NOOP)
```

- 두 실행 로그에서 데모 고객 이름·전화·생년월일 문자열은 0건이었다.
- 엔진은 프로세스 안 스텁(`ga.engine.mode=stub`, `demo/engine-table.json`)이다. 응답은 운영과 같은 계약·정합성 검증을 거친다.
- A-1-REQUEST의 INS-F 상품은 표에 없어서 `UNAVAILABLE(NO_RATE_DATA)`다. 산출불가 흐름의 시연이다.

## 9. 엔진(E3·E3.1)

- **E3**: 심사 승인에 따라 PR #1을 merge commit `98d5e50`으로 병합했다. 태그 `phase-e3`(`fe36c7c`)는 main에서 도달 가능하다.
- **E3.1**: PR [#2](https://github.com/hjryoo-ai/ga-commission-engine/pull/2), head `fe6f9e7`. 보고서는 `commission-system/docs/phase-E3.1-보고서.md`다.
  - CI는 직접 조회했다. PR run `36707884896`은 빠른·풀(Oracle)·no-docker가 전부 success였다. push run `36707878119`는 success였다(풀 티어는 push 대상이 아니라 skipped).
  - 항목(심사 §3 1~8 + §4-4)
    - UPSTREAM → `9379be96`.
    - 키 규칙 400, 외부 키 컬럼 40.
    - GET 403 설명.
    - Oracle SEQUENCE 채번: 동시 50건 실패 0. 주입에서는 옛 코드가 7/50 ORA-00001로 실패했다.
    - CLAUDE.md 신설.
    - JCS 상호 검증 1,051건: 엔진 라이브러리가 짝 없는 서로게이트를 받아들이던 것을 찾아 거부하도록 고쳤다.
    - `TEMP_PRODUCT` 제거.
    - no-docker 잡.
    - 토큰 해시 목록(최대 2).
  - 테스트는 10,188건이다(E3 9,111건 대비 +1,077).
  - 엔진이 올린 질문은 2건이다. 심사에 넘긴다.
    - ① 공통 마이그레이션 번호가 이미 적용된 Oracle 전용 V103보다 작다. 앞으로 V104부터 올리자는 제안이다.
    - ② `DISC_GRADE_SNAPSHOT_ITEM.product_key` 폭(129)도 40으로 줄일지.
  - 스냅샷 번호가 7자리(1,000,000부터)로 바뀌었다. 이 저장소는 스냅샷 ID를 불투명하게 다루므로(`OPAQUE_CODE` ≤ 64) 영향이 없다.
- 병합은 심사 뒤에 한다.

## 10. 3B(봉인) 질문

1. **서식 항목 ↔ 값 결속.**
   - 문제: 3A는 출처별 존재 판정(D2)이다. 봉인 렌더에는 "어느 항목에 어느 값"이 필요하다.
   - 권장: 서식 `render`에 닫힌 어휘의 결속 키를 추가한다(예: `bind: CATALOG_DEFAULT|ENGINE_GRADE_LABEL|ENGINE_RANK|RECOMMENDATION|PANEL_INSURERS`). 코드는 항목 코드를 모른 채 결속 키로 분기한다.
   - 서식 형식 변경은 첫 운영 배포 전이라 제자리 수정이 가능하다. AGENT 항목의 과대 충족(`TEST_ONLY_FIELD`)도 이 결속으로 사라진다.
2. **봉인 조건의 순서와 결과.**
   - 조건: `SealGate`(승인), 스냅샷 노후(`snapshotMaxAgeDays`), 소급 룰 재해석(B1 `RULE_SUPERSEDED_DRAFT`), R-FIELD-REQUIRED 등 SEAL 단계 전체.
   - 권장: 모두 업무 거부로 분류한다(커밋·감사·플래그). 실패 목록을 한 번에 돌려준다.
3. **canonical JSON 구성.**
   - 항목 순서는 `item_no`, 스냅샷 결과는 항목 순서(D8)로 한다.
   - `field_values`는 `{code: value}`(origin은 제외할지), 성명은 봉인 시점 복호화(감사 `CUSTOMER_VIEW` 1행)로 한다.
   - 권장: origin은 증거 패키지에만 둔다.
4. **확인서 번호 채번.**
   - `{tenant}-{yyyy}-{seq}`는 테넌트·연도별로 다시 세야 한다(형식이 요구). 엔진처럼 SEQUENCE 하나로는 안 된다.
   - 권장: `(tenant_id, year)` 카운터 행을 `INSERT … ON CONFLICT DO UPDATE SET seq = seq + 1 RETURNING seq`로 만든다. 한 문장이라 경합이 없다(행 잠금으로 직렬화). 동시 50건 테스트를 넣는다.
5. **봉인 산출물 암호화의 키 단위.**
   - 심사가 정한 것: "해당 문서 데이터 키 파기"(crypto-shredding).
   - 권장: 문서별 DEK를 테넌트 KEK로 감싸 `document_key` 테이블에 둔다(Phase 2 볼트와 같은 형태, 파기 = 감싼 키 NULL).
   - 대안: SSE-KMS의 테넌트 키. 이 경우 문서 단위 파기가 불가능하다.
6. **준법 큐의 플래그 유형.** 3A는 `VALIDATION_OVERRIDE`(대상에 규칙 ID)다. 설계서 §5 주석의 `TEMP_PRODUCT`·`GRADE_UNAVAILABLE` 유형으로 나눌지 묻는다. 권장은 유지이며, 큐 화면(Phase 6)이 대상의 규칙 ID로 묶는다.
7. **승인 뒤 플래그 해소.**
   - 3A는 승인해도 `VALIDATION_OVERRIDE` 플래그를 닫지 않는다.
   - 권장: 봉인 성공 시 그 확인서의 오버라이드 플래그를 "승인됨"으로 해소한다(해소자 = 승인자). 승인만으로는 닫지 않는다(대상이 다시 바뀔 수 있다).
8. **재기준(rebase) 명령**(B1): COMPARED 회귀 + 새 ID 고정, 기존 승인 무효(규칙이 바뀌었으므로)로 할지 묻는다.
