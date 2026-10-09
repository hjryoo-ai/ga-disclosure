# Phase 6B 완료 보고 — 준법 큐·계약 연결·징구율·청약 게이트·초안 폐기·보존 재계산·고객 등록 API

작성 2026-10-09 · 대상 지시문 `docs/phase-06B-지시문.md` · 계획 `docs/phase-06B-계획.md`(승인 2026-10-09, `docs/phase-06B-계획승인.md` — 계획 §A가 본문보다 우선)
- 승인 뒤 회신(전부 2026-10-09): 중간 결정 ①~⑦, 7단계 회신, 8단계 보안 회신, 9단계 회신, 10단계 회신(조건 3 재작성), §9 별도 승인(R1·R2).
- 설계서 v1.14(변경 이력 ①~㉓) · 브랜치 `work/phase-6B` · PR [#10](https://github.com/hjryoo-ai/ga-disclosure/pull/10)
- **병합은 수용 심사 회신 뒤**에 한다. 태그 `phase-6B`는 이 보고서 커밋에 단다.

## 요약

- **준법 큐(지시문 §2, G1~G3)**: 플래그 유형은 닫힌 11개 — 설계서 `flag-types` 블록 ↔ DB CHECK ↔ 모든 룰 번들 ↔ 코드 상수를 네 방향으로 대조한다(`FlagTypeTableTest`). 담당·SLA·설계사 가시성·해소 코드는 룰 `complianceQueue.types` 데이터이고, 플래그가 열릴 때 복사해 고정한다. `CHAIN_BROKEN`은 플래그 뒤에 시작한 `VERIFY_TENANT`의 MATCH 근거로만 닫힌다. 설계사는 플래그를 보지 못한다(6A 수용심사 §2 ①).
- **계약 연결(§3, G4)**: 인바운드 배치 계약 `contracts/contract-link/v1`(JSON·CSV), 청약번호 → 증권번호 순 매칭, 활성 연결 1건/확인서·증권, 보존기한은 Phase 4 산식으로 연장만, 배치 원장(같은 참조 다른 내용 = `BATCH_REF_REUSED`), 피드 출처 범위 `SOURCE`, 정정 새 버전 봉인 때 이월·무효 보유자의 인수(V19). `PolicyLinked` v2에는 번호가 없다(2026-10-09 결정).
- **징구율(§4, G5)**: **내부 지표 — 규제 정의 없음**. 산식은 룰 enum(`LINKED_COMPLETED_BY_CONTRACT_DATE` 기본, 안 B 보존), 스냅샷은 불변(같은 달·룰 버전 409), `inputs_hash`로 재현, 묘비·`ABANDONED` 제외, 정정은 계산 시점 기준.
- **청약 게이트(§5, G6)**: `POST /internal/v1/gate`가 옛 GET stub을 대체, 판정은 Phase 4 `GateFunction` 그대로(176개 입력 전수 동일), 판정마다 감사, 무효 보유자는 근거가 아니다, 주체별 분당 한도 429, mTLS 주체 대조. **8단계 보안 검토에서 경로 판정 우회 둘을 찾아 고쳤다**(§6).
- **초안 폐기(§6, G7)**: 상태 `ABANDONED`(봉인 전 4상태에서, 종단), 묘비 함수 `ga_draft_abandon`(전용 롤), 법적 보류 존중(V17), 방치 배치는 룰 `draft.abandonAfterDays`(null이면 0건).
- **보존 재계산(§7, G8)**: 작업 `RETENTION_RECOMPUTE` — 시행 중(ACTIVE) GLOBAL 버전만, 기본 dry-run, 더 길 때만 쓰기(세 겹), 파기 건 제외, 커밋 뒤 잠금 재적용, 감사 전후값.
- **고객 등록 API(§8, G9)** — 별도 승인 절(§2 ④): 응답은 가명·영수증뿐, 응답 집합은 성공·400·429·422뿐이고 다른 고객의 존재를 반영하지 않는다, 동적 센티널 누출 스캔 0건, 고객 검색 경로 없음. 승인 R1로 **요청 본문의 모르는 필드는 앱 전체 400**.
- **6A 반영 2건(G10)**: 플래그 목록 설계사 404, 400 뒤 멱등 키 해제(V13).
- **테스트 13,642건, 실패 0, 스킵 0**(6A: 12,204건). 위반 주입 151건 전부 의도한 테스트에서 잡혔다(§3). 첫 시도에 잡히지 않은 것 1건(T4)은 규칙을 넓힌 뒤 잡혔고, 무효 주입 1건(P4 — 컴파일 실패)은 세지 않고 P4b로 다시 돌렸다.
- **CI**: PR #10의 run `37926046428`(head `125d1e9`) — `build`·`pdfa-verify`·`no-docker` 전부 success. CI 테스트 보고서는 로컬과 같은 13,642건(실패 0, 스킵 0)이고 모듈별 수도 같다. 6B의 다른 커밋도 푸시마다 CI success(§3 CI).
- **데모**: 격리 컨테이너(15432/18333 — 사용자 compose 볼륨 무관)에서 `seed.sh` 2회(2회째 전부 NOOP)와 `http-demo.sh` 2회(2회째 쓰기 전부 재생). 6B 데모 전 항목 — 계약 피드 CSV 연결·보존 연장·미매칭, 징구율 스냅샷, 게이트 ALLOWED·BLOCKED, 초안 폐기, `CHAIN_BROKEN` 거부 → 복구 → 해소, 보존 재계산 dry-run, HTTP 고객 등록 → 초안(§4). HTTP 데모 첫 시도는 관리자 확인에서 멈췄다(의도된 기기 재사용 탐지 — §4)
- **엔진**: 6B는 엔진에 새로 요구하는 것이 없어 E4를 열지 않았다(승인 §5). 엔진 저장소는 손대지 않았다.

## 1. 커밋·파일

커밋 목록(`main..work/phase-6B`, 보고서 커밋은 이 표 뒤에 붙는다):

| 커밋 | 계획 §11 | 요약 |
|---|---|---|
| `602d865` | 0 | 6A 수용심사 §2: 관리자·준법 플래그 목록, 저장하지 않은 응답 뒤 멱등 키 해제(V13) |
| `8e7ec74` | — | docs: 6B 계획(승인 대기, §9 별도 심사) |
| `390c3e2` | — | docs: 계획 승인 반영 |
| `b167beb` | 1b | 정정의 관리자 칸·HTTP 경로 삭제, 멱등 요청 해시 HMAC(**6A 결함, 6B 수정**) |
| `325c01c` | 2 | V14 — 계약 연결·묘비·플래그 가드·징구율 스냅샷 |
| `a969081` | 3 | 룰 키 — 준법 큐·징구율 산식·초안·계약 연결·게이트, `kpi` 제거 |
| `709ad91` | 2(보완) | V15 — 연결 쪽에서도 현재값 대조, 초안 연결 금지, 파기 감사에 연결 번호 해시 |
| `b2b1ae2` | 4 | 준법 큐 — 정책 복사, 배정, 수동 해소, `CHAIN_BROKEN` 근거, SLA 스윕 |
| `13d300e` | 5 | 계약 연결 — 배치·매칭·보존 연장, `PolicyLinked` v2(번호 없음), V16 |
| `42c2fef` | 5(보완) | 무효·정정된 확인서가 쥔 증권 = AMBIGUOUS(배치 전체 실패 아님) |
| `72ae20a` | 6 | 초안 폐기 — 명시 폐기·방치 배치·묘비 함수 |
| `9bfea4b` | 6(보완) | V17 — 폐기가 법적 보류를 지킨다 |
| `2469596` | 중간 결정 | ①②④⑤⑦ — 배치 원장·피드 출처·보류/파기 잠금·감사 대상 인덱스(V18) |
| `7980fa6` | 중간 결정 | test: 요청마다 닫히는 HTTP 클라이언트(§D-2) |
| `52f30e8` | 중간 결정 ③ | 연결 이월·인수(V19) |
| `78bca7c` | 7 | 징구율 스냅샷 작업·범위 조회 |
| `117707e` | 7(회신) | 거부 코드 표 양방향 닫힘, "정정은 한 번" = 계산 시점 |
| `4e0fb6b` | 8 | 청약 게이트 |
| `4d9b0f0` | 8(보안) | 경로 판정을 라우팅된(디코딩) 경로로 |
| `619f08f` | 8(보안 회신) | 파서 하나 — 채널은 매칭된 라우트, mTLS 경로는 `PathPatternRequestMatcher` |
| `16c477c` | 9 | 보존 재계산 |
| `d80c128` | 9(회신) | 재계산 룰은 ACTIVE만 |
| `08e9e89` | 10 | 고객 등록 API·카탈로그 검색 |
| `cfbf173` | 10(§9 승인) | R1 모르는 필드 앱 전체 400, R2 V20 감사 인덱스 |
| `125d1e9` | 11 | CLI `flags list/resolve`, 6B 데모(`seed.sh` Phase 6B 절, `http-demo.sh` 고객 등록) |

주요 추가 파일:

| 영역 | 파일 |
|---|---|
| DB | `V13`~`V20`(V13 멱등 해제 가드, V14 6B 본 DDL, V15 연결 정합, V16 보험사 코드 CHECK 오기, V17 폐기 보류, V18 원장·출처·잠금·인덱스, V19 이월, V20 감사 인덱스), `docker/postgres/init-roles.sql`(`disclosure_abandoner`), `docs/db-error-codes.md` GD130~GD139 |
| 계약 | `contracts/contract-link/v1`(배치 스키마·샘플), `disclosure-api` 2.7.0, `disclosure-internal` 4.0.0, 룰 스키마(`complianceQueue`·`collectionRate`·`draft`·`contractLink`·`gate`·`customers`), `contracts/verify/v1/retention-recompute-report.schema.json`, 이벤트 `DisclosureAbandoned`·`PolicyLinked` v2, `CHECKSUMS` |
| 룰 | `rules.metric.CollectionRates`(순수 산식), `EffectiveRule` 접근자, `FlagTypePolicy`·`CollectionRateFormula` |
| 워크플로 | `flag/{FlagCommandService, FlagPolicyResolver, …}`, `contract/{ContractLinkService, ContractLinkCsv, LinkCarry}`, `rate/CollectionRateService`, `gate/GateService`, `disclosure/{DraftAbandonService, RetentionRecomputeService}`, `customer/CustomerRegistrationService`, `catalog/CatalogQueryService`, `job/JobWork.admit` |
| 인프라 | `persistence/{ContractLinkRepository, CollectionRateRepository, RetentionRecomputeRepository, CustomerRegistrationLimitRepository}`, `retention/AbandonGateway`, `crypto/{RequestHashKey, CustomerReceiptKey}` |
| API | `internal/{InternalContractLinksController, InternalGateController}`, `rest/{FlagsController 명령, CollectionRatesController, CustomersController, CatalogController}`, `security/{BoundPrincipal, ClientCertSubjectFilter, IdempotencyKeyArgumentResolver}` |
| 앱·데모 | `config/ClientCertHeaderGuard`(`prod`), CLI `flags`·`contract-links`·`collection-rates`·`gate`·`drafts`·`retention recompute`, `demo/{disclosures-6b, signatures-6b, http-customer}.json`, `scripts/seed.sh` Phase 6B 절, `http-demo.sh` 고객 등록 |

## 2. 지시문 추가 보고

### ① 플래그 유형 표 (룰 ↔ 코드)

정본은 설계서 부록 D `flag-types` 블록이다. `FlagTypeTableTest`가 블록 ↔ 마지막 마이그레이션의 `ck_compliance_flag_type` ↔ 모든 RULE 번들의 `complianceQueue.types` 키 ↔ 코드가 올리는 유형을 대조하고, 블록의 `raised_by` 클래스가 실제로 그 유형을 참조하는지, 문자열로 유형을 넘기는 호출이 열거된 상수만 쓰는지 본다.

| 유형 | 올리는 곳 | 담당(규제 번들) | 수동 해소 |
|---|---|---|---|
| GRADE_INCONSISTENT | DisclosureService | COMPLIANCE | 예 |
| VALIDATION_OVERRIDE | DisclosureService·SealService·LifecycleService | MANAGER | 아니오(문서 상태로) |
| RULE_SUPERSEDED_DRAFT | SealService·LifecycleService | MANAGER | 아니오 |
| IDENTITY_FAILED | SignSessionService | COMPLIANCE | 예 |
| SIGNATURE_DEVICE_REUSE | SignService | COMPLIANCE | 예 |
| PAPER_SCAN_REVIEW | SignService | MANAGER | 아니오 |
| SIGN_EXPIRED | ExpireService | COMPLIANCE | 예 |
| CHAIN_BROKEN | TenantVerifier | COMPLIANCE | 예(근거: 플래그 뒤 MATCH) |
| NOTIFY_FAILED | NotificationDispatcher | COMPLIANCE | 예 |
| RULE_DRIFT | RuleBundleReconciler | COMPLIANCE | 예 |
| RULE_ACTIVATION_MISSED | RuleActivationJob | COMPLIANCE | 예 |

SLA 시간은 전부 `null`(지어내지 않는다 — §14 #18), 설계사 가시성은 전부 `false`. 옛 목록의 MISSING·LATE·GRADE_UNAVAILABLE·TEMP_PRODUCT·EXPIRED는 플래그 유형이 아니다(설계서 §6.8).

### ② 징구율 정의와 근거

- **내부 지표 — 규제 정의 없음**(`INTERNAL_METRIC_NO_REGULATORY_DEFINITION`). 이름을 "규제 징구율"로 쓰지 않는다. 응답·보고서·CLI 머리줄에 정의 표기·산식 ID·룰 버전을 함께 싣는다.
- 산식(룰 `collectionRate.formula`, 닫힌 enum):
  - `LINKED_COMPLETED_BY_CONTRACT_DATE`(기본, 안 A): 분모 = 기준월(계약일의 KST 달) 활성 `contract_link`, 분자 = 그중 연결 확인서가 COMPLETED이고 완료일(KST) ≤ 계약일.
  - `TARGET_INCLUDING_UNMATCHED`(안 B): 분모에 같은 달 미매칭을 더한다 — 대상 계약 판정(§14 #6)이 정해질 때까지 쓰지 않는다.
  - 공통: 파기·ABANDONED 제외, 조직은 작성 시점 `org_path`별 + 테넌트 전체, 정정·이전으로 닫힌 행은 입력 아님 — "정정된 연결은 한 번"은 **계산 시점 기준**(7단계 회신 ①).
- 근거: 공개 자료는 "확인서 징구율 및 기재 내용의 정확성을 주기적으로 점검"까지만 쓰고 분모·분자를 정하지 않는다 — 보험신보 「보험GA 준법·내부통제 체크리스트 북 — 보험상품 비교·설명」(2026-06-29, https://www.insweek.co.kr/news/articleView.html?idxno=71510). GA협회 업무지침 원문은 찾지 못했다(§14 #17).

### ③ 상태표 diff

```diff
 DRAFT,COMPARE,COMPARED
 DRAFT,VOID,VOID
+DRAFT,ABANDON,ABANDONED
 ...
 COMPARED,VOID,VOID
+COMPARED,ABANDON,ABANDONED
 ...
 GRADED,VOID,VOID
+GRADED,ABANDON,ABANDONED
 ...
 REASONED,VOID,VOID
+REASONED,ABANDON,ABANDONED
```

`ABANDONED`는 종단이다(나가는 전이 없음, 번호·체인 무관). 지시문의 "`VALIDATED`"는 이 시스템의 상태가 아니어서 봉인 전 상태 전부로 읽었다(지시문 오기, 계획 §12 Q16).

### ④ 고객 등록 API — 별도 절

개인정보를 HTTP로 **받는** 첫 경로다. 계획 §9, 승인 §4(조건부 승인 여섯 조건), 10단계 회신(2026-10-09 — 조건 3 재작성)을 따른다. 이 절은 2026-10-09 §9 별도 승인을 받았다(조건 1~6 충족) — 반영 지시 R1·R2는 `cfbf173`에 반영했고 아래 9.4 5·6에 적었다.

#### 9.1 범위

- `POST /api/v1/customers` — 설계사만(`CUSTOMER_REGISTER` AGENT SELF, 인가 표 한 칸 넓힘). 본문 `{name, phone?, birthDate?}`, 상한 4 KiB. 응답 201 `{customerRef, receiptId}`, `Location` 없음.
- `GET /api/v1/catalog/products?group&q&insurer` — 설계사·관리자·준법(`CATALOG_READ` TENANT, 인가 표 한 줄). 기준일 오늘(KST), 개인정보 없음.
- 만들지 않은 것: 고객 검색·상세 GET. 고객을 읽는 경로는 없다.
- 계약 `disclosure-api` 2.7.0, 룰 키 `customers.registerPerMinute`(번들 4개 재해시), 설계서 §7 표·§9 "고객 등록 API"·§14 #22·변경 이력 ㉒.

#### 9.2 조건 → 테스트 → 주입

| 조건 | 테스트(클래스.메서드) | 주입(제거 전 실패 확인) |
|---|---|---|
| 1. 요청 본문이 로그·감사·멱등 저장소·예외·응답 어디에도 없고, 멱등 해시는 §2의 HMAC(`b167beb`) | `CustomerRegisterIT.theHttpRegistrationIsThePhase2RegistrationAndTheReceiptIsDerived`(멱등 행의 `request_hash` = HMAC(키 파일, JCS 입력) ≠ SHA-256, 영수증 튜플 키 = `body`뿐, `body` 키 = `customerRef`·`receiptId`, 행에 요청 값 없음), `CustomerRegisterIT.malformedInputIsA400WithTheFieldNameOnly`(400은 `details.field`만, 응답에 보낸 값 없음, 형식 오류는 감사 행을 남기지 않음), `ApiPlaintextLeakScanIT.customerRegistrationLeaksTheRequestsOwnValuesNowhere`(로그·출력·응답·DB 덤프·DB 서버 로그) | C1a 요청 해시를 키 없는 SHA-256으로 → `CustomerRegisterIT` 실패. **C1b(지시 G11 최소) 고객 등록 본문을 멱등 영수증에 저장** → `CustomerRegisterIT`(튜플 키 `[body, request]`)·`ApiPlaintextLeakScanIT` 실패. C1c 컨트롤러가 이름을 INFO 로그 → `ApiPlaintextLeakScanIT` 실패 |
| 2. 응답은 가명과 등록 영수증뿐 | `CustomerRegisterIT.theResponseIsThePseudonymAndReceiptOnlyAndANoopAfterExpiryIsTheSameBytes`(키 집합 = `customerRef`·`receiptId`, `Location`·재생 표시 없음), 모든 응답이 계약(`additionalProperties: false`) 검증을 지난다(`ApiTestSupport.send`) | C2 응답에 `created` 추가 → 계약 검증으로 `CustomerRegisterIT` 4건 실패 |
| 3. (회신으로 다시 씀) 등록 응답은 성공(재생·NOOP 포함 동일 바이트), 형식 오류 400, 한도 429, 키 재사용 422뿐이며, 어떤 응답도 다른 고객의 존재를 반영하지 않는다. 같은 사람을 두 번 등록하면 서로 다른 가명 둘이 나온다. | `CustomerRegisterIT.theResponseIsThePseudonymAndReceiptOnlyAndANoopAfterExpiryIsTheSameBytes`(첫 등록 → 재생 같은 본문 + `Idempotency-Replayed` → 멱등 기록 만료 뒤 같은 키 = 상태·헤더(−Date)·본문 **바이트 동일**, Phase 2 감사 `CREATED,NOOP`, 같은 사람 다른 키 = 201·가명 둘, 다른 주체 같은 키 = 다른 고객, 같은 키 다른 본문 = 422 `IDEMPOTENCY_KEY_REUSED`), `CustomerRegisterIT.noResponseReflectsWhetherTheSamePersonAlreadyExists`(처음 등록·같은 설계사 재등록·다른 설계사 등록·다른 사람 — 가명·영수증 값 자리만 빼면 지문 동일), `CustomerRegisterIT.theLimitIsRuleDataPerSubjectA429ThatReleasesTheKeyAndHoldsUnderConcurrency`(룰 한도 3에서 동시 8건 → 201 셋·429 다섯, 429 본문 바이트 고정, 저장·감사 없음, 멱등 행 없음 = 키 해제, 다른 설계사는 영향 없음), `CustomerRegisterIT.malformedInputIsA400WithTheFieldNameOnly` | C3a NOOP를 "이미 등록됨" 거부로 → 실패. C3b 한도를 422로 → 실패(429 0건). C3c 한도 미적용 → 실패(201 여덟). C3d 주체 잠금 없이 집계 → 실패(동시 8건 중 201 넷). C3e 한도를 상수로(룰 무시) → 실패. C3f 영수증을 난수로 → 실패(만료 뒤 바이트 불일치·파생 불일치) |
| 4. 이름·전화·생년월일 검색 엔드포인트가 라우트 집합에 없다 | `ApiRouteSetIT.theOnlyCustomerRouteIsRegistrationAndNoGetSearchesByPersonalData`(실제 MVC 매핑: "customer"가 든 라우트는 `POST /api/v1/customers` 하나, GET 핸들러에 개인정보 이름의 질의 매개변수 없음; 세 계약 문서: 고객 GET 없음, 개인정보 질의 매개변수 없음), `OpenApiContractIT.routesAndContractAgreeBothWays` | C4 `GET /api/v1/customers/search?name=` 추가 → `ApiRouteSetIT`·`OpenApiContractIT` 실패 |
| 5. 누출 스캔은 실행 중 생성한 동적 센티널(그 요청의 실제 값)로 | `ApiPlaintextLeakScanIT.customerRegistrationLeaksTheRequestsOwnValuesNowhere` — 요청마다 무작위 이름(한글 6음절)·전화(010 + 8자리)·생년월일(1930~2000). 금지 표기: 이름, 전화 하이픈 없음·있음, 생년월일 `yyyy-MM-dd`·`yyyyMMdd`, 각 UTF-8 hex(7명 × 10 + 그 밖의 보낸 값 3 × 2 = 76). 경로: 201(첫 등록·재생·만료 뒤 NOOP·하이픈 없는 표기)·400(틀린 전화·미래 생년월일·모르는 필드 `rrn`·4 KiB 초과·객체 자리)·422(키 재사용)·404(관리자)·429(한도 1 테넌트). 대상: 응답(헤더·본문), 루트 TRACE 로그(JDBC TRACE·`ga.access` 포함), 표준 출력·오류, DB 전체 덤프, DB 서버 로그 — 0건. 실패 메시지는 센티널 번호만 싣는다 | C1b·C1c(위). C5 요청 DTO `toString`이 값을 찍음 → `ApiPlaintextLeakScanIT`(TRACE 로그)·`ApiLayerRulesTest` (f)(전화·생년월일) 실패 |
| 6. Phase 2 `register` 규약(암호화·가명·중복)을 그대로 쓰고 HTTP 계층에 판단이 없다 | `CustomerRegisterIT.theHttpRegistrationIsThePhase2RegistrationAndTheReceiptIsDerived`(등록 키 = `api:` + hex(SHA-256(주체 ‖ 0 ‖ 멱등 키))[0..40], 암호화 컬럼·없는 값 NULL, 감사 detail 키 = Phase 2의 다섯), `CustomerRegisterIT.onlyAgentsHaveTheCellEveryoneElseGetsTheSame404`(본문 해석은 인가 뒤 — 관리자·준법은 틀린 본문도 같은 404), `ApiLayerRulesTest.eachMappingMethodCallsExactlyOneUseCaseMethod`·`AuthorizationCoverageTest` | C6a 등록 키에서 주체를 뺌 → 실패. C6b 컨트롤러가 인가 전에 본문을 해석 → 실패(관리자의 틀린 본문이 400) |

조건 밖 보강 주입: V1 4 KiB 라우트 상한 제거 → `CustomerRegisterIT` 실패. V2 모르는 필드 허용 → 실패. V3 미래 생년월일 허용 → 실패. V4 스케줄러에 카탈로그 칸 → `AuthzMatrixTest`만 실패(`CatalogSearchApiIT`는 통과 — 서비스 주체는 채널 분리로 `/api`에서 어차피 404. 인가 표가 유일한 방어선이라는 기록).

주입 기록: 19건 전부 의도한 테스트가 실패하고 제거 뒤 통과. C1b·V2는 실패 메시지 정리(아래 9.5) 뒤 다시 돌려 같은 결과를 확인했다. R1 뒤 V2(이 DTO의 특례 제거)는 앱 설정 주입 R1a로 대체된다.

#### 9.3 응답 필드

- 성공 201 본문: `customerRef`(`CR-` + 32 hex), `receiptId`(UUIDv8). **그 밖의 필드 없음.**
- 헤더: 공통 보안·캐시 헤더뿐 — `Location` 없음, 재생일 때만 공통 `Idempotency-Replayed: true`.
- 오류: 공통 `Problem`(`code`·`message`·`details`) — 400은 `details.field`만(값 없음), 429 `RATE_LIMITED`·422 `IDEMPOTENCY_KEY_REUSED`·404 `NOT_FOUND`는 `details` 비어 있음.

#### 9.4 계획 §9 본문과 다르게 한 것

1. **조건 3 — 지시 조건 3의 전제 오류, 계획 원안 복귀**: 승인 §4 조건 3("한도 초과도 같은 422, 존재하는 고객 중복 판정도 같은 응답")은 Phase 2가 이름·전화로 중복을 본다는 전제였다. 실제로는 등록 키로만 보므로 "이미 등록됨" 신호가 없다. 회신대로 한도는 계획 §9.6 원안 429(키 해제)로, 응답 집합 문장은 회신 문안 그대로 바꿨다.
2. **영수증 = 결정론적 파생**(회신 ②): `receiptId` = HMAC-SHA256(영수증 키, `ga-customer-receipt/v1` ‖ 0 ‖ 테넌트 ‖ 0 ‖ 가명 ‖ 0 ‖ 등록 키)의 앞 16바이트를 UUIDv8 모양으로. 계획(§A-7)은 "등록 감사 행의 ID"였다. `audit_log`는 순번뿐이고, 감사 행은 Phase 2 그대로 둔다. 키는 **별도 파일** `ga.api.receipt-key-file`로 요청 해시 키와 따로 뒀다. 같이 쓰면 요청 해시 키를 바꿀 때 만료 뒤 NOOP의 영수증도 바뀐다. 영수증 키 자체를 바꾸면 그 뒤 NOOP 응답이 첫 응답과 달라진다(배포 노트). CLI는 프로세스마다 새 키(HTTP 영수증을 내지 않는다).
3. **미래 생년월일**: 계획 §9.2는 "값객체가 미래 날짜를 거부한다"였는데 Phase 2 `BirthDate`에는 그 검사가 없었다. 값객체에 `BirthDate.parse(raw, latest)`(시계 없음 — 기준일을 받는다)를 더했고, 기준일은 유스케이스가 준다. 기존 `parse(raw)`와 CLI 수입은 그대로.
4. **본문 해석은 인가 뒤**: 유스케이스가 `Function<LocalDate, NewCustomer>`를 받아 인가 뒤에 부른다(게이트와 같은 순서). 칸이 없는 주체에게 400이 라우트 존재를 알리지 않는다. 컨트롤러는 유스케이스 메서드 하나만 부른다(아키텍처 규칙).
5. **모르는 필드 400**: 10단계 커밋은 이 DTO만 `@JsonAnySetter`로 모아 거부했다(앱의 JSON 읽기는 모르는 필드를 무시했다). **승인 R1로 앱 전체로 바꿨다**(`cfbf173`): `FAIL_ON_UNKNOWN_PROPERTIES`, 400 `MALFORMED_REQUEST` + `field` = 그 필드 이름, 특례 제거. 기존 테스트는 하나도 고치지 않고 통과했다(Phase 7로 미룰 필요 없음). `CustomerRegisterIT.unknownFieldsAreRejectedOnEveryRoute`(확인서 작성·법적 보류), 주입 R1a·R1b. 스칼라 숫자 → 문자열 강제 변환은 그대로(값객체가 검사한다). 객체·배열은 400.
6. **한도 집계 위치**: 감사 `CUSTOMER_REGISTER` 행을 주체·시각으로 센다(계획대로 — 감사 로그를 한도 상태로 쓰는 선택은 수용됨). **승인 R2로 V20 `audit_log(tenant_id, actor_subject, action, at)` 인덱스**(`cfbf173`, `V20AuditIndexIT` — 통계 수집 뒤 한도 문장의 계획이 그 인덱스를 쓴다, 주입 R2).
7. **`ApiLayerRulesTest` (f)는 `name`을 민감 성분으로 보지 않는다**: 상품 이름 등에 너무 흔한 이름이라서다. 요청 DTO의 이름 노출은 누출 스캔이 잡는다(C5에서 확인).
8. **카탈로그 검색**은 계획 §11 10단계 범위대로 같이 넣었다. 결과에 쪽 나눔은 없고, 키워드는 100자·제어문자 없음.

#### 9.5 시험 출력

- 동적 센티널의 크기 단언이 실패하면 값 목록이 출력에 실린다. 첫 실행에서 내 계산 실수(7명을 9명으로)로 한 번 실패해, 무작위 허구 값이 로컬 시험 보고서 XML에 실렸다. 크기만 단언하도록 고쳤고, 보고서는 다음 실행이 덮었다(커밋된 적 없음).
- `CustomerRegisterIT`의 형식 오류·저장 행 단언도 실패 메시지가 요청 본문을 싣지 않도록 사례 번호·불리언으로 바꿨다(주입 C1b의 실패 메시지에 허구 이름이 실린 것을 보고 고침).

#### 9.6 테스트 건수(10단계 추가)

`CustomerRegisterIT` 7(R1 +1), `ApiPlaintextLeakScanIT` 1, `ApiRouteSetIT` 1, `CatalogSearchApiIT` 1, `V20AuditIndexIT` 1, `BirthDateBoundTest` 8, `ContractSchemaTest` +3, `EffectiveRuleComplianceKeysTest` +1.

## 3. 테스트와 완료 기준

### 테스트 수 (`125d1e9`에서 `TZ=UTC ./gradlew check --continue`)

| 모듈 | 소스셋 | 테스트 |
|---|---|---|
| platform-core | test | 3,067 |
| platform-canonical | test | 1,054 |
| platform-spring | test | 12 |
| disclosure-domain | test | 1,094 |
| disclosure-rules | test | 2,442 |
| disclosure-seal | test | 63 |
| disclosure-sign | test | 515 |
| disclosure-audit | test | 141 |
| disclosure-workflow | test | 1,372 |
| disclosure-infra | integrationTest | 3,727 |
| disclosure-app | archTest | 66 |
| disclosure-app | integrationTest | 89 |
| **합계** | | **13,642**(실패 0, 스킵 0) |

6A(12,204건) 대비 +1,438. 큰 증가는 `CollectionRateFormulaTest`(1,005 — 시드 고정 생성기 1,000건 + 예시), `GateServiceTest`(180 — Phase 4 함수와 입력 전수 대조), 룰 스키마·키 시험이다. 통합 테스트는 Docker 없이 실패한다(스킵하지 않는다 — 기존 `no-docker` CI 작업).

### 완료 기준

| # | 기준 | 증거(테스트 클래스·메서드) |
|---|---|---|
| G1 | 플래그 유형 양방향, 룰만 바꿔 정책 변화, SLA null → `due_at` NULL | `FlagTypeTableTest`(7), `ComplianceQueueRulesIT.theBundlePolicyIsCopiedWhenAFlagOpens`·`changingOnlyTheRuleChangesRoleVisibilityDueAndCodes`·`manualResolutionFollowsTheRuleShape`·`slaBreachIsAColumnAndAnAuditRowNotANewFlag`·`assignmentGoesToASubjectLinkedInTheAssignedRole` |
| G2 | `CHAIN_BROKEN`: MATCH 없는 해소 422, 플래그 이전 MATCH 422, 이후 MATCH로 해소·근거 감사 | `ChainBrokenResolutionIT.chainBrokenClosesOnlyWithAMatchThatStartedAfterTheFlag`(실제 HTTP 작업 경로, 변조 → MISMATCH → 복구 → MATCH), `Phase6BCliIT`(CLI) |
| G3 | 설계사는 `visible_to_agent=false` 플래그를 어디서도 못 봄, 관리자 ORG | `FlagVisibilityIT.anAgentSeesNoFlagNotEvenOnItsOwnDisclosure`·`aManagerSeesFlagsOfDisclosuresUnderItsOrgOnly`·`theQueueFiltersByAssignedRoleAndDueAndAgentsHaveNoCommandRoute` |
| G4 | 스키마 통과만, 매칭 순서·AMBIGUOUS·UNMATCHED, 보존 연장만, 활성 1건, 파기 대기 종료 | `ContractLinkIT`(8 — `linkCorrectAndReimportKeepOneActiveLinkAndOnlyExtendRetention`, `unmatchedAmbiguousUnsealedAndMismatchedItemsOnlyLeaveReportRows`, `aLinkEndsTheContractDateWaitOfDestruction`, …), `ContractLinkBatchParserTest`, `ContractLinkApiIT`, `V14GuardIT`·`V15GuardIT`·`V19GuardIT` |
| G5 | 산식 enum, 스냅샷 불변·재계산 거부, 파기 뒤 동일, 묘비·ABANDONED 제외, `inputs_hash` 재현 | `CollectionRateIT`(3), `CollectionRateFormulaTest`(1,005), `CollectionRateApiIT`, `CollectionRateCliIT`, `V14GuardIT`(유일 키) |
| G6 | Phase 4 함수와 동일 판정(전수), 요청마다 감사, 응답 개인정보 0, `GATE_CLIENT` 외 404 | `GateServiceTest`(176 입력 전수 + 4), `GateApiIT.decisionsFollowTheCandidateRuleAndAVoidedHolderIsNeverEvidence`·`thePerMinuteLimitAnswers429WithoutAnAudit`, `ClientCertGuardIT`(2), `GateCliIT` |
| G7 | 상태표 ↔ 코드, 배치 기준일 룰, 지정 컬럼 NULL·해시 감사, 고객 파기에서 제외, 함수 ↔ `pii-columns` | `DisclosureStateTableTest`, `AbandonDraftIT`(6), `PiiColumnTableTest`, `TombstoneIT`(pii-columns 전 컬럼), `V14GuardIT.anAbandonedDraftIsNotALiveDisclosure…` |
| G8 | 연장만, dry-run 무변경, 파기 건 제외, 잠금 재적용, 감사 전후값 | `RetentionRecomputeIT`(3), `RetentionRecomputeApiIT`, `RetentionRecomputeCliIT` |
| G9 | 본문이 로그·감사·멱등·응답에 0, 가명만, 한도, 검색 경로 부재 | §2 ④ 표(조건 1~6) — `CustomerRegisterIT`(7), `ApiPlaintextLeakScanIT`, `ApiRouteSetIT`, `V20AuditIndexIT` |
| G10 | 플래그 목록 설계사 404, 400 뒤 키 해제 | `FlagVisibilityIT.anAgentSeesNoFlagNotEvenOnItsOwnDisclosure`, `IdempotencyIT.aMalformedRequestReleasesTheKeySoTheCorrectedRequestIsProcessed`·`notFoundIsNotStoredAndReleasesTheKey`·`releaseRemovesOnlyTheReleasingInProgressClaim`, `V12GuardIT` |
| G11 | Phase 0~6A 무손상, 평문·jqwik·BOM, 위반 주입 기록 | 전체 check(위 수), `PlaintextLeakScan`류, `NoJqwikTest`·BOM 규칙(기존), 아래 주입 표 — 지시문 최소 8건은 W3·X1·S4/K1·L1·S2/Y1·W4·P1·C1b |

### 규칙 테스트 위반 주입 기록 (주입 → 실패 확인 → 제거)

전부 의도한 테스트가 실패했고, 제거 뒤 통과했다. 상세(무엇을 바꿨고 어느 메서드가 실패했나)는 각 커밋 본문에 있다.

| 커밋 | 주입 | 잡은 테스트 |
|---|---|---|
| `602d865` | Q1~Q7(설계사 플래그 칸, 코드만의 칸, 관리자 범위 무시, 400·404 뒤 미해제, 순번 없는 해제, V13 가드 미변경 2종) | `FlagVisibilityIT`, `AuthzMatrixTest`, `IdempotencyIT`, `V12GuardIT` |
| `b167beb` | R1~R4(관리자 정정 칸, 정정 HTTP 경로, 키 없는 요청 해시, 키 파일 권한 검사 제거) | `ScopePolicyTest`, `OpenApiContractIT`, `IdempotencyIT`, `RequestHashKeyTest` |
| `325c01c` | S1~S9(GD136 끔, 평 UPDATE로 ABANDONED, 해소된 플래그 변경, **스냅샷 유일 키 제거**, 폐기가 추천 행 삭제, 상태표 블록 누락, pii-columns 누락, ABANDONED를 live로, 정정 FK 제거) | `V14GuardIT`, `DisclosureStateTableTest`, `PiiColumnTableTest` |
| `a969081` | T1~T9(DB CHECK 미기재 유형, 번들 유형 누락, 코드의 미기재 유형, 배치의 문자열 유형, 담당 불일치, 올리는 곳 불일치, 스키마의 옛 `kpi`, 징구율 사규 덮어쓰기, null 가능 키 누락) — **T4는 첫 실행 미검출 → 문자열 호출 스캔을 넓혀 검출** | `FlagTypeTableTest`, `ContractSchemaTest`, `EffectiveRuleComplianceKeysTest` |
| `709ad91` | U1~U7(정정·새 연결 재대조 없음, 초안 연결, 현재값 있는 생성, 파기 감사의 번호 해시 누락 2종, 픽스처 미충전) | `V15GuardIT`, `TombstoneIT` |
| `b2b1ae2` | W1~W7(플래그 이전 MATCH 수용, MISMATCH 수용, **CHAIN_BROKEN 근거 조건 제거**, **`visible_to_agent` 무시**, null SLA 기본값, 스윕 재표시, 관리자 해소 칸) | `ChainBrokenResolutionIT`, `ComplianceQueueRulesIT`, `AuthzMatrixTest` |
| `13d300e`·`42c2fef` | X1~X10(**연결 시 보존 단축 허용**, 청약번호 무시, 여러 매칭 중 첫 건, 다른 확인서에서 재연결, 이벤트에 번호, 감사에 원번호, 작업 행에 항목, 인가 전 해석, V16 되돌림, 옛 보유자 판정) | `ContractLinkIT`, `ContractLinkApiIT` |
| `72ae20a`·`9bfea4b` | Y1~Y9(**DELETE로 폐기**, 함수가 값 유지, 파기 읽기 누락, 잠금 아래 재확인 없음, 읽기를 변경으로, 플래그 열린 채, 관리자 칸, 사유 되풀이, null 룰을 0으로), Z1~Z3(보류 앱 검사 없음, DB 검사 없음, 고객 보류 무시) | `AbandonDraftIT`, `AuthzMatrixTest` |
| `2469596` | I1~I10(같은 참조 다른 내용 수용, 출처 무관 인가, 출처 목록 무시, 보류 트리거 무잠금, 폐기·파기 전제의 공유 잠금 없음 2종, 파쇄 키 미계산, 지워진 대상 보류, `feed_sources` 비필수, 대상 인덱스 없음) | `ContractLinkIT`, `ContractLinkApiIT`, `LegalHoldIT`, `V18GuardIT` 등 |
| `52f30e8` | J1~J6(인수 끔, 유효 보유자 인수, 다른 고객 인수, 봉인 때 이월 없음, 다른 증권으로 이월, 이월 행을 활성으로 셈) | `ContractLinkIT`, `V19GuardIT` |
| `78bca7c` | K1~K12(**유스케이스에서 재계산 허용**, 유일 키 제거, ABANDONED·파기 포함, 이월 연결 입력, UTC 완료일, 관리자에게 테넌트 행, 제출 전 검사 생략, 월말 버전 무시, 실행일 룰, 관리자 실행 칸, 끝나지 않은 달) | `CollectionRateIT`, `CollectionRateApiIT`, `V14GuardIT`, `AuthzMatrixTest`(K11만) |
| `117707e` | R1~R5(블록에 없는 거부 코드, `BATCH_REF_REUSED` 누락 — 원래 놓친 것, 지도 밖 새 거부 enum, 범주 변경, 코드 없는 블록 행) | `RejectionCategoryTableTest` |
| `4e0fb6b` | L1~L11(**게이트 감사 생략**, 상태만 보는 판정, VOID 후보, 한도 무시, 인가 전 해석, mTLS 가드 끔, prod 가드 제거, 멱등 예외 제거, 불일치가 확인서를 가리킴, 감사에 식별자, 오늘 룰) | `GateServiceTest`, `GateApiIT`, `ClientCertGuardIT`, `ApiLayerRulesTest` |
| `4d9b0f0`·`619f08f` | M1(가드를 원 URI로), M2(채널을 원 URI로), N1·N2(파서 하나 정리 뒤 같은 되돌림) | `ClientCertGuardIT`, `ChannelSeparationIT` |
| `16c477c`·`d80c128` | P1~P12(**재계산에 단축 경로**, 쓰기의 "더 길 때만" 제거, dry-run 쓰기, 파기 대상 포함, 묘비 쓰기, 스케줄러 칸, 감사 생략, 잠금 재적용 생략, 트랜잭션 안 잠금, RETIRED 사용, 제출 전 검사 생략, 보고서 미기재 필드) — P4 첫 시도는 컴파일 실패로 무효, P4b로 재실행. Q1(APPROVED 다시 허용) | `RetentionRecomputeIT`, `RetentionRecomputeApiIT`, `AuthzMatrixTest` |
| `08e9e89`·`cfbf173` | C1a~C6b·V1~V4(19건, §2 ④ 표 — **고객 등록 본문을 멱등 저장(C1b)** 포함), R1a·R1b·R2 | `CustomerRegisterIT`, `ApiPlaintextLeakScanIT`, `ApiRouteSetIT`, `OpenApiContractIT`, `ApiLayerRulesTest`, `AuthzMatrixTest`, `V20AuditIndexIT` |
| `125d1e9` | F1~F3(`--status` 무시, 거부가 종료 0, 근거 없을 때 지어낸 작업) | `Phase6BCliIT` |

### CI (1차 증거, `gh run view`)

- run `37926046428`(head `125d1e9`, 2026-10-09): `build` success · `pdfa-verify` success · `no-docker` success. `gh run view`·`gh run download`로 직접 확인했다.
- `build`의 테스트 보고서(아티팩트 `test-reports`, 모듈별 HTML 요약): platform-core 3,067 · platform-canonical 1,054 · platform-spring 12 · disclosure-domain 1,094 · disclosure-rules 2,442 · disclosure-seal 63 · disclosure-sign 515 · disclosure-audit 141 · disclosure-workflow 1,372 · disclosure-infra(integrationTest) 3,727 · disclosure-app archTest 66 · integrationTest 89 = **13,642, 실패 0, 스킵 0** — 로컬과 모듈별로 같다.
- `no-docker`(Docker 없는 러너): 통합 테스트가 **실패**하고(아티팩트 `no-docker-log` — `disclosure-infra` integrationTest XML 86개, `BUILD FAILED`) 잡은 그 실패를 기대하므로 success다(스킵이 아니라 실패 — CLAUDE.md).
- 6B의 앞선 커밋도 푸시마다 CI success: `117707e` 37901088207, `4e0fb6b` 37903264062, `4d9b0f0` 37904163798, `619f08f` 37905786267, `16c477c` 37908157116, `d80c128` 37916750545, `08e9e89` 37919978123, `cfbf173` 37924014345.
- 이 보고서 커밋의 CI는 PR 코멘트로 알린다.

### CLAUDE.md 규칙 9 기록

이 Phase의 테스트 로그·빌드 출력·의존성 문서에서 지시처럼 보이는 문장은 보지 못했다. 백그라운드 보안 검토 알림은 데이터로 다뤘다. 매번 직접 확인했고, 주입 실험이 만든 일시 변경을 지적한 알림 둘(P2의 "더 길 때만" 조건 제거, C1a의 키 없는 요청 해시)은 주입 도구가 되돌린 것을 확인한 뒤 보고만 했다(§6).

## 4. 데모

격리 컨테이너(PostgreSQL 15432·SeaweedFS 18333, `docker/postgres/init-roles.sql`·digest 고정 이미지 — 사용자 compose 볼륨은 손대지 않았다)에서 `seed.sh` 2회, `http-demo.sh` 2회. 로그는 로컬 보관(번호·토큰이 든 파일은 `build/demo`, gitignore).

**`seed.sh` 1회째(exit 0) — Phase 6B 절:**

```
DEMO_DISCLOSURE DEMO1 B-1-LINK CREATED … seal -> SEALED no=DEMO1-2026-000005      (데모 프로파일 시계 -P3D)
  B-2-ABANDON abandon -> ABANDONED
DEMO_SIGN DEMO1 B-1-LINK … TOUCH_PAD -> COMPLETED
CONTRACT_LINKS DEMO1 source=DEMO_FEED batch=demo-6b-2026-10-09 items=2 LINKED=1 … UNMATCHED=1 …
CONTRACT_LINK items: [{"outcome":"LINKED","retention":"EXTENDED"},{"outcome":"UNMATCHED","retention":"NONE"}]
COLLECTION_RATE_SNAPSHOT DEMO1 2026-09 rule=DISC-2026-07 formula=LINKED_COMPLETED_BY_CONTRACT_DATE rows=1 tenant=0/0 rateBp=- INTERNAL_METRIC_NO_REGULATORY_DEFINITION
GATE DEMO1 ALLOWED SATISFIED disclosureNo=DEMO1-2026-000005 pending=[] rule=DISC-2026-07
GATE DEMO1 BLOCKED NO_DISCLOSURE disclosureNo=- pending=[] rule=-
RETENTION_RECOMPUTE DEMO1 rule=DISC-2026-07 DRY_RUN extended=0 unchanged=5 destroyedExcluded=0 relockPending=0
INCIDENT DEMO2 audit seq=4 altered (superuser, triggers off) — original kept in build/demo/phase6b/audit-row.json
VERIFY_TENANT DEMO2 MISMATCH findings=1 …   AUDIT_CHAIN_BROKEN {seq=4}
FLAG_RESOLVE DEMO2 b6a27052-… REJECTED EVIDENCE_REQUIRED
RESTORED DEMO2 audit seq=4 from build/demo/phase6b/audit-row.json (backup)
VERIFY_TENANT DEMO2 MATCH findings=0 …
FLAG_RESOLVE DEMO2 b6a27052-… CHAIN_BROKEN RESOLVED VERIFIED_MATCH
```

- 징구율 2026-09는 0/0이다 — 데모 연결의 계약일(어제)이 이번 달이라 지난달 분모에 없다. 스냅샷 1개월의 시연이고 수치 자체는 의미가 없다.
- 보존 재계산 dry-run이 `unchanged=5`인 것은 B-1-LINK가 이미 계약일 앵커로 연장됐고 나머지는 같은 룰의 기간이라서다.

**`seed.sh` 2회째(exit 0) — 전부 NOOP:** 확인서·서명 NOOP(데모 규칙), 폐기 `NOOP (already ABANDONED)`, 계약 피드 재수입 `NOOP=1 UNMATCHED=1`(미매칭 보고 행은 1건 그대로 — DB에서 확인), 스냅샷 `NOOP (snapshot exists)`, `CHAIN_BROKEN DEMO2 NOOP`, 재계산 dry-run 같은 표. 게이트는 질의라 실행마다 판정 감사 한 줄이 남는다(상태 변화 없음 — 설계대로).

**`http-demo.sh`:**
- 첫 시도(실행 ID 기본값) 2회는 관리자 확인에서 422 `ACKNOWLEDGEMENT_MISSING`으로 멈췄다. 원인: HTTP로 등록한 새 고객이 seed의 C01과 같은 데모 기기 지문으로 서명해 대리 서명 탐지가 `SIGNATURE_DEVICE_REUSE`를 올렸다(의도된 탐지 — 6A 데모는 같은 고객이라 나지 않았다). 데모를 고쳐 관리자가 6B `GET /api/v1/disclosures/{id}/flags`로 플래그를 읽고 확인하게 했다(6A 보고서 질문 "관리자가 플래그 ID를 찾는 경로"의 답).
- 고친 뒤 새 실행 ID(`HTTP_DEMO_RUN=2026-10-09b`)로 2회, 둘 다 exit 0:

```
STEP register 201 replayed=no
REGISTERED customerRef=CR-300ec0fa… receiptId=b91d666e-7486-8a9b-… (fields: ["customerRef","receiptId"])
CATALOG PG-HEALTH-SIMPLE-NR asOf=2026-10-09 products=4
STEP create 201 … STEP seal 200 → SEALED … DEMO1-2026-000007
… PUBLIC verify-identity 200 {"missing":[],"passed":true} … PUBLIC capture 200
FLAGS … [{"type":"SIGNATURE_DEVICE_REUSE","status":"OPEN"}]
STEP manager-confirmation 200 replayed=no → STATUS … COMPLETED
FEED … ack … JOB VERIFY_TENANT … SUCCEEDED, REPORT result=MATCH
REJECTIONS wrong-token / query-token / used-token: identical (HTTP/1.1 404 )
```

  2회째는 등록을 포함한 쓰기 전부 `replayed=yes`(같은 가명·같은 영수증), 고객 경로는 COMPLETED라 건너뜀.
- 고객 등록 본문은 허구 파일(`demo/http-customer.json`)을 그대로 보낸다 — CLI 인자·환경변수에 개인정보가 없다. 날짜별 멱등 키라 다른 날의 실행은 새 가명이다(§14 #22).

## 5. 설계서와 달리 구현했거나 해석한 지점

| 단계 | 지점 | 근거·판정 |
|---|---|---|
| 1b | 멱등 요청 해시를 전 라우트 HMAC으로(키 파일 `ga.api.request-hash-key-file`) | 승인 §2 — **6A 결함, 6B 수정** |
| 2 | V14 정의자 롤에 `disclosure.status` 등 UPDATE 부여(폐기 함수용) | 권한 넓힘으로 기록 — 함수만 그 롤로 실행 |
| 3 | 사규 덮어쓰기는 최상위 키 단위(계획은 유형 단위) | 기존 규약 유지, 보고 |
| 4 | 준법 큐 유스케이스를 `workflow.flag`에(계획은 compliance 모듈 — 인가가 workflow에만 있다) | 레이어 제약 |
| 4 | `FLAG_ASSIGN` 관리자 ORG 칸 | 인가 표 넓힘 ▲(계획 승인에서 표시) |
| 5 | 쓰기 허용 목록 +2(`mirrorContractLink`·`extendRetention`), `JobRunner.submitWithBody`(본문 입력 작업) | 닫힌 목록 갱신 |
| 5 | V16 — V14의 보험사 코드 CHECK 오기 수정 | 엔진 계약 1.2.0 패턴 |
| 6 | 마지막 변경 = 변경 감사의 최댓값, 방치 배치는 실행일 ACTIVE 룰 | 계획 §6 해석 |
| 중간 ③ | 인수 대상에 EXPIRED 포함 | **지시 문언 밖, 정의 안**(회신) |
| 7 | 순수 산식을 `rules.metric`에(계획은 compliance — workflow·compliance가 서로 못 닿는다) | 수용(7단계 회신) |
| 7 | 스냅샷 룰 = 그 달 마지막 날 시행 GLOBAL(실행일 아님) | 기본 조회와 일치, 정정 = 소급 GLOBAL 버전 |
| 7 | `JobWork.run(acquired, jobIds)`·`JobWork.admit`(제출 전 거부) | 작업 행을 만들기 전 409/422 |
| 8 | 분당 한도를 인가 뒤 유스케이스 안에서(계획: 유스케이스 전) | 칸 없는 주체에게 429가 보이지 않게 |
| 8 | 게이트는 멱등 예외 라우트(닫힌 목록 `EXEMPT_ROUTES`) | 재생된 옛 판정 방지 |
| 8 | `prod` 프로파일 첫 등장(mTLS 헤더 설정 없으면 기동 실패) | 운영 배치 전제 |
| 8 | 본문 해석은 인가 뒤(발견: 스케줄러의 빈 본문이 400으로 경로 존재 노출) | 게이트·연결·고객 등록 공통 |
| 9 | 재계산 룰은 ACTIVE만 | 9단계 회신 |
| 9 | `extendRetention`이 `destroyed_at IS NULL`도 요구 | 좁히는 변경, 수용 |
| 10 | §2 ④ 9.4 표(조건 3 재작성, 영수증 파생, 미래 생년월일, 인가 뒤 해석, 모르는 필드 → R1, 인덱스 → R2) | §9 별도 승인 |
| 11 | CLI `flags list/resolve` 신설(지시문 §9 CLI 목록에 있었으나 4단계에서 HTTP만 냈다) | 이 단계에서 |
| 11 | 데모 확인서 파일에 `applicationNo`·`abandon`(선택 필드) | 데모 편의, 운영 동작 아님 |
| 11 | 데모 `CHAIN_BROKEN`은 로컬 superuser psql로 감사 행 한 줄을 바꿨다가 되돌린다 | 시험(`ChainBrokenResolutionIT`)과 같은 모양, 앱에 superuser 경로는 없다 |
| 11 | 지시문 CLI `retention recompute --dry-run` | 기본이 dry-run이고 적용은 `--apply yes` |
| 11 | 지시문 CLI `collection-rate snapshot` | 명령 이름은 `collection-rates snapshot|list`(복수, 조회와 짝) |

## 6. 보안 검토 반영

- **`709ad91` V15** — V14 점검: 연결 쪽 변경이 확인서 현재값과 어긋날 수 있었다(GD136이 확인서 쪽만 봄), 봉인 전 초안에 연결이 붙으면 폐기 묘비에 증권번호가 남을 수 있었다, 파기가 연결 번호를 지우기만 하고 해시를 남기지 않았다(절대 규칙 2). 셋 다 고쳤다(U1~U7).
- **`42c2fef`** — 보안 리뷰 알림(본문 없음) 뒤 자체 점검에서 찾은 정합 결함: 무효·정정된 확인서의 활성 연결을 못 봐 배치 전체가 매번 실패.
- **`9bfea4b` V17** — 6단계 점검: 폐기 함수가 법적 보류를 보지 않아 보류 중인 초안의 자유 텍스트를 지울 수 있었다.
- **`4d9b0f0`·`619f08f` — 경로 판정 우회 둘(8단계)**:
  - **M1**(게이트 커밋 `4e0fb6b`에서 생김): mTLS 주체 대조가 원 URI를 비교해 `/internal/v1/%67ate`처럼 퍼센트 인코딩한 표기로 우회됐다.
  - **M2**(**6A부터** 있던 결함): HTTP 채널을 원 URI 접두로 정해 `/%69nternal/v1/…`가 사람 역할을 **API 채널로** 내부 핸들러에 보냈다. 분류: **불변식 위반, 데이터 영향 없음**(인가 표는 그대로 적용돼 권한 상승은 아니다 — "영향 없음"이 아니다). **6A 수용 심사가 놓친 공백**으로도 기록한다.
  - 자동 검토가 M1을 지적했고, 그것을 확인하는 중에 같은 부류의 더 오래된 M2를 찾았다.
  - 첫 수정의 자체 정규화(세 번째 파서)를 회신 ①대로 바꿨다 — **파서가 둘이면 우회가 생긴다**: 디스패치 전에 판정해야 하는 mTLS 경로만 Spring Security `PathPatternRequestMatcher`(MVC와 같은 파서), 나머지(채널)는 매칭된 라우트 템플릿(`BoundPrincipal.caller`). 인코딩 표기 시험은 그대로 둔다. 10단계 4 KiB 라우트 상한도 같은 매처를 쓴다.
  - 공개 경로 균일 거부 문장을 "체인에 도달한 요청의 거부는 전부 같은 바이트; 방화벽 거부는 체인 밖이며 토큰·테넌트 정보를 담지 않는다"로 좁혔다(`PublicSignUniformResponseIT` — `/public;x=1/v1/sign/open`이 토큰·테넌트와 무관하게 같은 응답).
- **운영 HTTP 클라이언트**(피드 소비자·TSA 어댑터)는 실패하면 닫힌다(확인 — 테스트 클라이언트 변경 `7980fa6`과 같은 패턴은 운영 코드에 없다).
- 주입 실험 중 백그라운드 검토가 지적한 것 둘(P2의 "더 길 때만" 조건 제거, C1a의 키 없는 해시)은 주입 도구가 만든 일시 변경이었고, 되돌림을 확인했다.

## D. 결함·공백 기록

| # | 분류 | 내용 |
|---|---|---|
| D-1 | **6A 결함, 6B 수정** | 멱등 요청 해시가 키 없는 SHA-256 — 정의역이 작은 본문(고객 이름·전화·생년월일)을 사전 대입으로 되돌릴 수 있었다. 6A 심사도 놓쳤다(승인 §2). `b167beb` |
| D-2 | **미증명 가설 + 테스트 전용 수정** | `7980fa6`: 간헐 실패를 연결 재사용 탓으로 보고 테스트 HTTP 클라이언트를 요청마다 새로 만들었다. 원인은 증명하지 못했다 — 전체 check에서 다시 나오면 포트·연결 로그로 원인을 증명한다. 이후 전체 check(로컬, 커밋마다 — 11회 이상)와 CI(푸시마다)에서 재발 없음 |
| D-3 | **테스트 범위 결함 + 수정** | 거부 코드 표 대조가 일부 계열만 봐서 `BATCH_REF_REUSED`(⑮)가 블록에 빠진 것을 놓쳤다. 시험이 `RejectionCategory.Categorized` enum 전부를 찾아 블록과 양방향 대조하게 했다(`117707e`) |
| D-4 | **불변식 위반, 데이터 영향 없음** | 6A부터 HTTP 채널이 원 URI 접두로 정해졌다(§6 M2) — 6A 심사 누락 |
| D-5 | 시험 공회전 + 수정 | 9단계 잠금 재적용 검사가 처음에는 잠금 기한이 전부 NULL이라 아무것도 검사하지 않고 통과했다 — 실제 저장소 잠금 날짜 대조와 커밋 뒤 호출 단언으로 바꿨다 |
| D-6 | 시험 출력 | 10단계 동적 센티널 크기 단언의 첫 실패가 허구 값을 로컬 보고서 XML에 실었다(커밋된 적 없음) — 실패 메시지를 번호·불리언으로 바꿨다(§2 ④ 9.5) |
| D-7 | 계약 ↔ 구현 불일치, 수정 | 계약은 `additionalProperties: false`인데 앱은 모르는 필드를 조용히 버렸다(6A부터) — R1로 앱 전체 400 |
| D-8 | 데모 잔존 상태 기록 누락 (**6B 수용 뒤 추가**, 2026-10-09 — 수용 심사 §3이 확인을 요청했고 D 표에 없었다) | HTTP 데모 첫 시도(실행 ID 기본값) 2회가 남긴 상태: `DEMO1-2026-000006`(`0bcdb6b6…`)은 봉인 → 원격 링크 → 고객 본인확인 → 서명 캡처까지 진행됐고, 관리자 확인이 422 `ACKNOWLEDGEMENT_MISSING`으로 거부돼 `SIGNATURE_DEVICE_REUSE` 플래그가 OPEN, 사용된 고객 링크 토큰과 함께 남았다. 재실행은 이 상태를 지우지 않고 같은 DB에서 새 실행 ID(`2026-10-09b`)로 했다(`DEMO1-2026-000007` COMPLETED). 데모가 끝난 뒤 격리 컨테이너(`ga6b-demo-pg`·`ga6b-demo-s3`)째 제거했으므로 지금은 어디에도 없다 — "지우지 않았다"는 재실행 동안에만 맞다. §4는 첫 시도가 멈춘 사실과 원인만 적고 잔존 상태를 적지 않았다 |

## 7. 엔진

6B가 엔진에 새로 요구하는 것이 없어 E4를 열지 않았다(승인 §5). 기존 3건(마이그레이션 번호 전역 단조 문서화, V104 `product_key` 폭 40, 엔진 계약 `format` → `pattern`)은 6B 수용 때 E3.2 정비 지시문으로 낸다. 엔진 저장소는 손대지 않았다.

## 8. Phase 7(화면) 질문

1. **협회 표준서식 라벨 확정(§14 #2)** — 화면·PDF의 항목명은 서식 번들 데이터다. Phase 7 화면은 확정 라벨을 받기 전 예시 라벨로 만든다(권장) — 확정 시 번들 버전만 올린다.
2. **같은 사람의 중복 가명 병합(§14 #22)** — 화면이 등록 직후 영수증의 가명만 쓰면 같은 사람이 다른 날 다시 등록될 수 있다. 병합 여부·주체(준법 수작업)·이전 대상(확인서·보류·파기 유예)을 Phase 7 전에 정할지.
3. **정정의 행위자와 재배정(§14 #20)** — 정정은 운영자 CLI 대리 실행뿐이다. 화면에 정정 경로를 둘지, 둔다면 누구의 칸인지.
4. **관리자의 플래그 확인 흐름** — 6A 질문(관리자가 플래그 ID를 찾는 경로)은 6B `GET /api/v1/flags`(관리자 ORG)로 생겼다. 관리자 확인 화면이 대리 서명 의심 플래그를 이 목록에서 고르게 할지(권장).
5. **룰 없는 테넌트의 쓰기 POST가 500**(4단계 관찰, 6A부터) — 멱등 청구가 룰을 읽다 실패한다. 테넌트 온보딩 순서(룰 배포 전 HTTP 차단)로 막을지, 503 같은 명시 응답으로 바꿀지.
6. **스칼라 숫자 → 문자열 강제 변환**(R1에서 그대로 둠) — 화면 클라이언트 계약에서도 허용으로 둘지.
7. **게이트 분당 한도가 인스턴스 메모리**(6A 질문 6과 같은 부류) — 다중 인스턴스 배치 전에 고객 등록처럼 DB 집계로 옮길지.
8. **계약 피드 주체의 작업 보고서 열람(`JOB_READ`)** — 지금은 준법만 결과를 본다. 피드 시스템이 자기 배치 결과를 읽게 할지.
9. **징구율 정의(§14 #17)** — 규제 정의 발견 시 enum 추가로 반영, 기존 스냅샷은 산식 ID로 구분(승인 조건 ②). 화면 표기는 "내부 지표 — 규제 정의 없음" 그대로.
10. **산식 불변 재계산(§14 #21)** — 입력 정정만으로 같은 달을 다시 계산하려면 새 룰 버전이 필요하다. 운영 부담이 되면 `computation_seq`.
