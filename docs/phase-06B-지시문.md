# Phase 6B 지시문 — 준법 큐·계약 연결·징구율·청약 게이트·초안 폐기·보존 재계산·고객 등록 API (v1.0)

## 역할과 맥락

`ga-disclosure` Phase 6A(`phase-6A`, PR #9) 수용 후. 대상은 설계서 **§4.4(게이트 산식), §6(상태표·준법), §9(보존·파기·개인정보), §12 Phase 6, §13(초안 보존), §14(#10 계약 피드·#15 외)** 와 `phase-05-수용심사.md` §4(결정 4·5·6·9)·`phase-06A-수용심사.md` §2. 화면은 Phase 7, 배포는 Phase 8. 이번 Phase는 **업무 기능의 유스케이스·룰·HTTP**이며, 외부 사실(계약 피드의 실제 출처·형식·주기)은 §14에 남고 우리 쪽 계약만 정의한다.

이 Phase가 끝나면 다음이 테스트로 증명돼야 한다.

1. 열린 플래그는 유형별 담당·SLA·해소 규칙이 **룰 데이터**이고, `CHAIN_BROKEN`은 `verify tenant` MATCH 없이 닫히지 않는다.
2. 계약 연결은 우리가 정의한 인바운드 계약으로만 들어오고, 연결은 확인서의 보존 앵커(`CONTRACT_DATE`)를 **연장만** 한다.
3. 징구율은 월별 스냅샷으로 저장되어 뒤의 파기·정정이 과거 수치를 바꾸지 않고, 묘비는 분자·분모 모두에서 빠진다.
4. 청약 게이트의 판정은 Phase 4 순수 함수 그대로이고, **요청마다 감사**되며 응답에 개인정보가 없다.
5. 버려진 초안은 삭제가 아니라 `ABANDONED` 묘비가 되고, 같은 파기 함수 패턴으로 개인정보 컬럼만 NULL이 된다.

## 시작 전 반영 (첫 커밋)

- 6A 수용심사 §2 ①(플래그 목록 엔드포인트, AGENT 404) ②(400·401·404·428·5xx는 멱등 키를 묶지 않음).
- 6A 보고서 §5 표를 계획 머리에(수용심사 단서).

## 시작 전 보고

계획에 ① V13 DDL(`contract_link`·`collection_rate_snapshot`·플래그 확장·`ABANDONED`·`application_no`) ② 상태표 변경(`ABANDONED` 전이 — `state-table` 블록 diff) ③ `contracts/contract-link/v1` 스키마 초안 ④ 징구율 정의식(분모·분자·제외·기준일)과 스냅샷 시점 ⑤ 게이트 계약(`/internal/v1/gate`)과 mTLS 구성 ⑥ 준법 큐 룰 스키마(`complianceQueue`) ⑦ 보존 재계산 작업의 안전장치(연장만·dry-run·감사) ⑧ 고객 등록 API의 **별도 절**(개인정보 수신 경로 — 입력 검증·저장 경로·감사·한도·누출 스캔) ⑨ **엔진 E4에 요구할 것**(없으면 "없음"이라고 명시)을 넣고 승인 후 진행. ⑧은 별도 심사 항목이다 — 계획의 다른 부분이 승인돼도 ⑧은 따로 승인한다.

## 작업 목록

### 1. V13 마이그레이션
- `contract_link(tenant_id, link_id, disclosure_id, policy_no, application_no NULL, contract_date, insurer_code, source, source_ref, received_at, linked_by)` append-only. 한 확인서에 활성 연결 1건(부분 유일), 정정은 새 행 + 이전 행 `superseded_by`. `policy_no`·`application_no`는 `pii-columns` EXTERNAL_ID 행(파기 함수 NULL 대상에 추가 — 함수 변경은 V13에서 CREATE OR REPLACE).
- `disclosure.application_no TEXT NULL`(작성 시 선택 입력, 매칭 보조 키, write-once) + `pii-columns`.
- `compliance_flag` 확장: `assigned_role`, `due_at`, `resolution_code`, `resolution_evidence JSONB`(닫힌 모양 — `verifyRunJobId` 등), `visible_to_agent BOOLEAN`(룰에서 복사, 생성 시 고정). `type`에 CHECK(닫힌 목록 — 룰 `complianceQueue.types`의 키와 양방향 대조 테스트).
- `collection_rate_snapshot(tenant_id, period_month, org_path, denominator, numerator, rate_bp, computed_at, rule_version_id, inputs_hash)` append-only, `(tenant_id, period_month, org_path)` 유일. `rate_bp`는 베이시스포인트 정수(double 금지).
- 상태 `ABANDONED` 추가: `disclosure.status` CHECK, 불변 트리거 분기(파기 함수와 같은 경유 조건), `abandoned_at`.
- 오류 코드 GD130~, `db-error-codes.md`.

### 2. 준법 큐 (`disclosure-compliance` + HTTP `/api/v1/flags`)
- 룰 `complianceQueue.types[type] = {assignedRole, slaHours NULL, visibleToAgent, resolutionCodes[], requiresEvidence}`(테넌트 오버라이드 가능; **SLA 기본값은 null** — 지어내지 않는다). 유형 목록은 지금 존재하는 플래그 유형 전부(대리 서명·종이 스캔 검토·`SIGN_EXPIRED`·`NOTIFY_FAILED`·`CHAIN_BROKEN`·`RULE_DRIFT`·`RULE_ACTIVATION_MISSED`·`PAPER_SCAN_REVIEW`·검증 오버라이드 등 — 코드에서 추출해 표로, 누락 시 테스트 실패).
- 유스케이스: `ListFlags`(범위 = 6A 인가 표), `AssignFlag`, `ResolveFlag(flagId, resolutionCode, evidence)`. **`CHAIN_BROKEN` 해소 조건**: `evidence.verifyRunJobId`가 가리키는 `VERIFY_TENANT` 작업이 플래그 생성 **이후**에 `SUCCEEDED`이고 보고서 `result=MATCH` — 아니면 422. 해소는 감사 `FLAG_RESOLVED`(사유 코드·근거), 플래그 재개는 불가(새 플래그).
- SLA 경과는 작업 `FLAG_SLA_SWEEP`이 `due_at < now`인 열린 플래그에 `SLA_BREACHED` 표시(새 플래그가 아니라 컬럼) + 감사. 스케줄은 Phase 8.
- 목록은 커서·필터(`status`·`type`·`assignedRole`·`dueBefore`), 응답에 개인정보 0(확인서 번호·가명만).

### 3. 계약 연결 (`contracts/contract-link/v1`, 인바운드 포트 + 가져오기)
- 계약 정의: `contract-link-batch.schema.json` — `{source, batchId, items:[{policyNo, applicationNo?, contractDate, insurerCode, customerRef(가명)?, productKey?}]}`. 실제 보험사·청약 시스템 피드 형식은 §14 #10 — **어댑터가 그 형식을 이 계약으로 바꾼다**(지금은 CSV/JSON 파일 어댑터 둘).
- `ImportContractLinks(caller, batch)`: 매칭 순서 `application_no` 정확 일치 → `policy_no` 정확 일치(둘 다 테넌트 안) → 매칭 0건이면 `UNMATCHED` 보고 행(저장은 `contract_link_unmatched` 테이블, 개인정보 없음 — `policy_no`만, 보존 룰 `contractLink.unmatchedRetentionDays` 뒤 삭제 허용 테이블). 매칭 다수면 fail-fast `AMBIGUOUS_MATCH`(건너뛰고 보고). 매칭 1건: `contract_link` INSERT → **`retention_until` 재계산(앵커 `CONTRACT_DATE`, 연장만 — Phase 4 함수 그대로)** → 감사 `CONTRACT_LINKED` → 아웃박스 `PolicyLinked`(기존 8개 이벤트 중 해당 유형이 있으면 그것, 없으면 추가형 + CHECKSUMS).
- 파기 판정의 `contractLinkWaitDays`는 이 연결이 있으면 끝난다(Phase 5 로직 변경 없음 — 테스트로 재확인).
- HTTP: `POST /internal/v1/contract-links`(서비스 주체 `CONTRACT_FEED` 역할 신설 — `identity_link` CHECK 목록에 추가, 서비스 단독), CLI `contract-links import --file --format csv|json`.
- 기존 `POST /disclosures/{no}/policy-link`(6B 표시)는 이 유스케이스의 단건 형태로 구현하거나 계약에서 제거 — 계획에서 결정.

### 4. 징구율
- **산식은 지어내지 않는다.** 규제 문서(설계서 §1)에 징구율 정의가 있으면 그것을 쓰고, 없으면 §14에 "정의 미확정"으로 두고 계획 ④에서 **후보 2안을 근거 문서와 함께** 올린다. 고정되는 것은 틀뿐이다: 기준월은 KST, 분모·분자는 `contract_link` 활성 행과 확인서 상태에서 계산하며, 묘비(`destroyed_at`·`ABANDONED`)는 양쪽에서 제외. 코드는 산식을 룰 데이터 `collectionRate.formula`(닫힌 enum)로 선택한다.
- 스냅샷: 작업 `COLLECTION_RATE_SNAPSHOT`이 전월(KST) 수치를 `org_path` 단위와 테넌트 전체로 계산해 append-only 저장(`inputs_hash` = 입력 확인서 번호 집합의 JCS 해시 — 재현 가능). 같은 달 재계산은 새 행이 아니라 **거부**(불변) — 정정이 필요하면 룰 버전을 올리고 새 `rule_version_id`로 새 행. 묘비 제외는 계산 시점 기준이고, 저장 뒤 파기는 수치를 바꾸지 않는다(테스트: 스냅샷 → 파기 → 재조회 동일).
- HTTP: `GET /api/v1/collection-rates?from&to&orgPath`(MANAGER=ORG 접두, COMPLIANCE=TENANT).

### 5. 청약 게이트 (`POST /internal/v1/gate`)
- 입력 `{applicationNo | policyNo, customerRef}`, 출력 `{decision: ALLOWED|BLOCKED, disclosureNo?, pendingRoles?[]}` — 개인정보 0. 산식은 Phase 4 `GateFunction` 그대로(`gateRequiresManager` 룰). 매칭 0건 → `BLOCKED(NO_DISCLOSURE)`.
- 역할 `GATE_CLIENT`(서비스 단독). mTLS는 인그레스 종단(Phase 8) + 앱은 클라이언트 인증서 주체를 헤더로 받아 `identity_link`와 대조(헤더 신뢰는 인그레스 뒤에서만 — 설정 키, 운영 프로파일 필수).
- **요청마다 감사** `GATE_DECISION`(입력 식별자·판정·확인서 번호·룰 버전). 응답 시간 패딩은 하지 않는다(내부 API). 한도는 서비스 주체 분당 룰.

### 6. 초안 폐기 (`ABANDONED`)
- 상태표에 `DRAFT|REASONED|VALIDATED(봉인 전 전부) → ABANDONED` 전이 추가(트리거: `AbandonDraft` 유스케이스 또는 배치). 조건: 마지막 변경 + 룰 `draft.abandonAfterDays` 경과(배치 `ABANDON_DRAFTS`), 또는 설계사 명시 폐기(사유 코드 룰 `draft.abandonReasons`).
- 폐기 시 같은 트랜잭션에서 **파기 함수 패턴**(`ga_draft_abandon` — 전용 정의자 롤 함수, 지정 컬럼 NULL: 추천사유 텍스트·`application_no`·항목의 자유 텍스트; `pii-columns`에 행 추가·G13 테스트가 네 번째 함수를 본다), 감사 `DRAFT_ABANDONED`(지운 값 해시 규약 동일). 번호 없음(봉인 전)이므로 체인·채번 무관. `customer_ref` 파기 판정의 "live 확인서"에서 `ABANDONED`는 제외.
- 아웃박스 `DisclosureAbandoned`(추가형).

### 7. 보존 재계산 (규제 변경)
- 작업 `RETENTION_RECOMPUTE --rule-version`(COMPLIANCE가 제출 가능, dry-run 기본): 지정 룰 버전의 보존기간으로 모든 봉인 이후 확인서의 `retention_until`을 재계산해 **연장만** 적용(Phase 4 트리거가 단축을 거부하므로 코드에 단축 경로 없음), 객체 잠금 재적용(`reconcile` 경로), 감사 `RETENTION_RECOMPUTED`(확인서별 이전·이후·룰 버전). 보고서 저장. 파기된 확인서는 제외.

### 8. 고객 등록 API (별도 심사 절)
- `POST /api/v1/customers`(AGENT): 이름·전화·생년월일을 받아 Phase 2 `CustomerRefService.register` 경로로 저장(암호화·가명 발급). 응답은 가명만. 요청 본문은 어떤 로그·감사·멱등 저장소에도 남지 않음(멱등 `request_hash`는 본문 해시이므로 괜찮지만 **본문 자체는 저장 안 함** — 재확인). 한도 룰 `customers.registerPerMinute`. 중복 판정은 Phase 2 규약. `GET /api/v1/customers/search?q=`는 **만들지 않는다**(이름 검색은 열거 경로) — 가명 또는 등록 영수증으로만 접근. Phase 7 화면은 등록 직후 영수증의 가명을 쓴다.
- 카탈로그 검색 `GET /api/v1/catalog/products?q&insurer`(AGENT·MANAGER·COMPLIANCE, 개인정보 없음).

### 9. CLI·데모
- CLI: `flags list/resolve`, `contract-links import`, `collection-rate snapshot`, `gate check`, `drafts abandon`, `retention recompute --dry-run`.
- 데모: 계약 피드 CSV 1건 가져오기(연결 → 보존 연장 확인) + 미매칭 1건, 징구율 스냅샷 1개월, 게이트 ALLOWED·BLOCKED 각 1, 버려진 초안 1건 `ABANDONED`, `CHAIN_BROKEN` 해소 거부(MATCH 없이) → `VERIFY_TENANT` 뒤 해소 성공, 보존 재계산 dry-run. HTTP 데모에 고객 등록 → 초안 흐름 추가. 2회 실행 NOOP.

## 완료 기준 (전부 테스트로 증명)

| # | 기준 | 증명 방법 |
|---|---|---|
| G1 | 플래그 유형 전수가 룰 `complianceQueue.types`와 양방향 일치, 담당·SLA·가시성·해소 코드가 룰만 바꿔 변함(코드 diff 0), SLA null이면 `due_at` NULL | `FlagTypeTableTest`·`ComplianceQueueRulesIT` |
| G2 | `CHAIN_BROKEN`: MATCH 없는 해소 422, 플래그 이전의 MATCH 422, 이후 MATCH로 해소·감사 근거 기록 | `ChainBrokenResolutionIT` |
| G3 | 플래그 가시성: AGENT는 `visible_to_agent=false` 플래그를 상세·목록 어디서도 못 봄(404/누락), MANAGER=ORG | `FlagVisibilityIT` |
| G4 | 계약 연결: 스키마 통과만 수용, 매칭 순서·`AMBIGUOUS`·`UNMATCHED`, 연결 시 `retention_until` 연장만(단축 시도 거부), 활성 연결 1건, 파기 대기 종료 | `ContractLinkIT` |
| G5 | 징구율: 산식이 룰 enum, 스냅샷 불변·재계산 거부, 파기 뒤 과거 수치 동일, 묘비·`ABANDONED` 제외, `inputs_hash` 재현 | `CollectionRateIT` |
| G6 | 게이트: Phase 4 함수와 동일 판정(동일 입력 전수), 요청마다 감사 1행, 응답 개인정보 0, `GATE_CLIENT` 외 404 | `GateApiIT` |
| G7 | `ABANDONED`: 상태표 블록 ↔ 코드, 배치 기준일 룰, 지정 컬럼 NULL·해시 감사, `customer_ref` 파기 판정에서 제외, 네 번째 함수가 `pii-columns`와 대조 | `AbandonDraftIT`·`PiiColumnTableTest` |
| G8 | 보존 재계산: 연장만, dry-run 무변경, 파기 건 제외, 잠금 재적용, 감사 전후값 | `RetentionRecomputeIT` |
| G9 | 고객 등록: 본문이 로그·감사·멱등 저장소·응답에 0(센티널), 가명만 응답, 한도, 검색 엔드포인트 부재(라우트 집합) | `CustomerRegisterIT`·`PlaintextLeakScanIT` 확장 |
| G10 | 6A 반영 2건(플래그 목록 AGENT 404, 400 뒤 키 해제) | `FlagVisibilityIT`·`IdempotencyIT` |
| G11 | Phase 0~6A 무손상, 평문·jqwik·BOM, 위반 주입 기록(최소: `CHAIN_BROKEN` 해소 조건 제거, 연결 시 보존 단축 허용, 스냅샷 재계산 허용, 게이트 감사 생략, `ABANDONED`가 DELETE, `visible_to_agent` 무시, 재계산에 단축 경로, 고객 등록 본문을 멱등 저장) | 빌드 로그·보고서 |

## 하지 말 것

- 징구율 산식을 지어내는 것(근거 문서 없이). 실제 피드 형식 추정. 이름 검색 API. 초안 DELETE. 보존 단축 경로. 게이트 응답에 고객 정보. 화면(Phase 7)·매니페스트(Phase 8).

## 보고 형식

6A와 동일. 추가로 ① 플래그 유형 표(룰 ↔ 코드) ② 징구율 정의와 근거 ③ 상태표 diff ④ 고객 등록 절의 누출 스캔 결과(별도 절) ⑤ Phase 7(화면) 질문 — 협회 표준서식 라벨 확정(§14 #2) 포함.
