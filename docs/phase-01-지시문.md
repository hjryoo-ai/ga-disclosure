# Phase 1 지시문 — 룰·서식 데이터 (v1.0)

## 역할과 맥락

당신은 `ga-disclosure` 저장소의 구현자다. Phase 0(`phase-0` 태그)은 수용됐고, 이번 Phase는 설계서 **v1.4** 기준 **§5(`rule_version`·`form_template`·`disclosure.tenant_rule_version_id`), §6.2, §6.3 (ii)~(v), §12 Phase 1, 부록 D**를 대상으로 한다. `CLAUDE.md`의 절대 규칙과 코드 규약은 그대로 적용된다.

Phase 1의 목적은 대전제 2 **"룰은 코드가 아니라 데이터다"** 를 실증하는 것이다. 이 Phase가 끝나면 다음이 테스트로 증명돼야 한다.

1. 규제 변경(최소 비교 개수, 관리자 확인 모드, 서식 항목)은 **번들 파일 교체만으로** 동작이 바뀐다 — 프로덕션 소스 diff 0.
2. 사규(TENANT 룰)는 규제(GLOBAL 룰)가 열어 둔 키만 덮어쓸 수 있고, 그 목록도 데이터다.
3. 어느 확인서에 어느 규제·사규 버전이 적용됐는지는 항상 두 ID로 답할 수 있다.
4. 테넌트에 복제된 규제 룰은 번들과 바이트 단위로 같고, 다르면 배치가 잡는다.

## 시작 전 보고

구현 계획을 먼저 보여주고 승인 후 진행한다. 계획에는 ① 선행 소과제 A~C의 처리 방식 ② RFC 8785(JCS) 라이브러리 후보와 선택 근거(테스트 벡터 통과 여부, Jackson 3 호환, BOM 충돌 여부) ③ V4 마이그레이션 DDL 초안이 들어간다.

## 선행 소과제 (Phase 0 이월)

- **A. 계약 정정.** `contracts/api/v1/engine-disclosure.openapi.yaml`을 설계서 v1.4 §4.1로 맞춘다: `results[]`를 `status` 분기 `oneOf`(OK는 6개 필드 필수, UNAVAILABLE은 `reason`만 필수·나머지 금지, 양쪽 `additionalProperties: false`), 최상위 `tieBreak`(`SHARED_RANK|STRICT`), 재조회 `GET /internal/v1/disclosure/commission-grades/{snapshotId}` 추가. `contracts/events/v1/envelope.schema.json`을 수용심사 4절의 포털 규약과 대조해 일치하면 `TODO(confirm-portal-4.1)` 제거, 다르면 diff를 보고서에 적고 포털 규약 쪽으로 맞춘다. `CHECKSUMS` 재생성.
- **B. 아키텍처 규칙 허용 목록.** `dbAccessOnlyVia`와 `RatioLabel.value()` 허용 범위를 **FQN 열거**로 바꾼다. `RatioLabel.value()` 허용에 `..seal.canonical..`, `..infra.persistence..`, `..infra.json..`을 추가한다(수용심사 3절 8번). 허용 목록의 각 항목이 존재하는지 검사하고 폐기 항목이 있으면 실패하는 테스트를 추가한다. 허용 클래스의 중첩 클래스는 `private`/package-private으로 좁힌다.
- **C. CI `no-docker` 잡**(원격 저장소가 생겼을 때만): `runs-on: ubuntu-latest` + `container: eclipse-temurin:25-jdk`로 `./gradlew :disclosure-infra:integrationTest --no-daemon`을 실행하고, 종료 코드가 0이 아니면서 출력에 스킵 0건·Testcontainers의 Docker 미발견 오류가 있을 때만 잡을 성공으로 판정한다. 원격이 없으면 워크플로 파일만 작성하고 보고서에 "미실행"으로 적는다.

## 작업 목록

### 1. `platform-canonical` 모듈 (Spring·DB 무의존, `com.ga.platform:platform-canonical:0.1.0` 발행)
- `Canonicalizer.canonicalize(JsonNode|String) → byte[]`(RFC 8785 JCS), `Sha256.of(byte[]) → Sha256`(도메인 값객체는 `disclosure-domain`에 있으므로 여기서는 hex 문자열 반환, 도메인이 감싼다).
- 라이브러리는 RFC 8785 부록의 테스트 벡터(숫자 직렬화·유니코드 정렬·이스케이프 포함)를 **전부 통과**해야 채택한다. 벡터를 `src/test/resources/jcs/`에 두고 테스트로 고정한다. 통과하는 라이브러리가 없으면 보고하고 멈춘다(자체 구현으로 대체하지 않는다).
- 의존 방향 갱신: `rules`·`seal`·`audit`·`compliance → platform-canonical`. ArchUnit 레이어 규칙과 `platform-core` 메인 의존 0 검사를 갱신한다.

### 2. V4 마이그레이션 (`disclosure-infra`)
- `rule_version`에 `source_bundle_id TEXT`, `bundle_hash TEXT` 추가. `CHECK ((scope='GLOBAL') = (source_bundle_id IS NOT NULL AND bundle_hash IS NOT NULL))`. `status`에 `CHECK (status IN ('DRAFT','APPROVED','ACTIVE','RETIRED'))`.
- `form_template`에 같은 두 컬럼 추가(NULL이면 테넌트 작성본), `template_type`에 `CHECK (template_type IN ('STANDARD','AUTO'))`.
- `disclosure`에 `tenant_rule_version_id TEXT` 추가. 본문 컬럼이므로 Phase 0의 메타 허용 목록 방식에 의해 자동으로 불변이어야 한다 — 이를 테스트로 확인한다(C7 매트릭스에 컬럼 1개 추가).
- 트리거 `trg_rule_version_guard_update`:
  - `body`·`apply_from`·`source_bundle_id`·`bundle_hash`·`scope`는 `OLD.scope='TENANT' AND OLD.status='DRAFT'`일 때만 변경 가능. GLOBAL은 항상 불변.
  - `apply_to`는 `OLD.apply_to IS NULL`일 때만 값을 쓸 수 있고, 값을 NULL로 되돌리거나 다른 값으로 바꾸는 것은 거부.
  - `status`는 `DRAFT→APPROVED→ACTIVE→RETIRED` 순방향 1단계씩만. 역방향·건너뛰기 거부. GLOBAL 복제본은 `APPROVED`로 삽입된다(INSERT 트리거: GLOBAL이면 `NEW.status='APPROVED'` 강제).
  - DELETE·TRUNCATE 거부(감사 대상). `form_template`도 같은 규칙(번들 출처면 불변, 테넌트 작성본은 미승인 상태에서만 수정 — `form_template`에는 status가 없으므로 `apply_from > 오늘`인 동안만 수정 가능으로 한다. 이 해석은 보고서에 적는다).
  - 오류 코드는 Phase 0 규약(`GDxxx`)을 이어서 배정하고 `docs/db-error-codes.md`에 목록을 유지한다.

### 3. 룰 번들과 배포 (`disclosure-rules` + `disclosure-infra` + `disclosure-app` CLI)
- 번들 파일 정본: `contracts/rules/bundles/rules/DISC-2026-07.bundle.json`, `DISC-2027-01.bundle.json`(부록 A-4 시나리오용: `minCompare: 4`, `managerConfirmMode: "OFF"`, `applyFrom: "2027-01-01"`, `supersedes: {"ruleVersionId": "DISC-2026-07"}`), `contracts/rules/bundles/templates/STANDARD-v1.bundle.json`. 번들 스키마 `contracts/rules/v1/rule-bundle.schema.json`: `{bundleId, kind: RULE|TEMPLATE, scope: GLOBAL, ruleVersionId|templateId, version?, applyFrom, applyTo?, supersedes?, body}`. `bundleId`는 `{ruleVersionId}@{bodyHash 앞 12자}`로 파일 안에 적고, 로더가 재계산해 다르면 거부한다.
- `bundle_hash = SHA-256(JCS(body))`. 룰 body는 기존 `rule-version.schema.json`을 통과해야 한다. 부록 D의 `tenantOverridable`·`allowedTieBreaks`를 스키마에 추가한다(`tenantOverridable`은 body 최상위 키 이름의 배열, 자기 자신·`validations`·`minCompare`·`signerSet`·`managerConfirmMode`·`subjectRule`·`gradeRequired`·`allowedGradingPolicies`·`allowedRankingPolicies`·`allowedTieBreaks`는 포함할 수 없음 — 이 금지 목록은 스키마의 `not/enum`으로 표현한다. 이것이 "사규는 규제를 완화할 수 없다"의 데이터 표현이다).
- 배포 명령(운영자 CLI, `disclosure-app`의 `ApplicationRunner`, 프로파일 `cli`): `rules distribute --bundle <path> --tenants all|T1,T2`.
  - 테넌트별로 한 트랜잭션: `supersedes`가 있으면 선행 룰의 `apply_to`를 자기 `applyFrom`으로 닫는다(선행 룰이 없거나 이미 닫혀 있고 값이 다르면 실패) → 복제본 INSERT(`status=APPROVED`, `source_bundle_id`, `bundle_hash`) → 감사 기록.
  - 멱등: 같은 `rule_version_id`가 이미 있고 해시가 같으면 no-op, 해시가 다르면 **거부**(규제가 바뀌었으면 새 `rule_version_id`다). 덮어쓰기는 존재하지 않는다.
  - `rules approve --tenant T1 --rule <id>`(TENANT 룰 DRAFT→APPROVED), `rules activate --as-of <date>`(활성화 배치 수동 실행). 인가는 Phase 6이므로 지금은 운영자 CLI만이며, `audit_log`에 `actor_role=OPERATOR`로 남긴다.
- 활성화 배치 `RuleActivationJob`(주입된 `Clock`): 테넌트별로 `APPROVED`이고 `apply_from ≤ today`면 `ACTIVE`, `ACTIVE`이고 `apply_to ≤ today`면 `RETIRED`. 순서는 RETIRED 처리 먼저(배타 제약 충돌 방지). 스케줄 등록은 Phase 6, 지금은 서비스 + CLI + 테스트.
- 번들 대사 `RuleBundleReconciler`: 테넌트의 GLOBAL 복제본마다 `SHA-256(JCS(body))`를 재계산해 `bundle_hash`·번들 파일과 대조. 불일치는 `compliance_flag(type=RULE_DRIFT)` INSERT(테이블은 V1에 있음). CLI `rules reconcile`.

### 4. 룰 해석기 (`disclosure-rules`, Spring·DB 무의존)
- 포트 `RuleVersionPort.findActive(TenantId, scope, LocalDate) → List<RuleVersionRecord>`(infra 어댑터는 기존 `RuleVersionRepository.findActiveOn` 확장). 포트는 "2건 이상이면 예외"를 **하지 않고** 목록을 그대로 돌려준다 — 판정은 해석기 몫이다.
- `RuleResolver.resolve(TenantId, LocalDate asOf) → EffectiveRule`:
  - GLOBAL 정확히 1건(0건 `NO_GLOBAL_RULE`, 2건 이상 `AMBIGUOUS`), TENANT 0~1건(2건 이상 `AMBIGUOUS`).
  - 병합: GLOBAL body 위에 TENANT body의 최상위 키를 덮되 `GLOBAL.body.tenantOverridable`에 있는 키만. 목록 밖 키가 TENANT body에 있으면 `DISALLOWED_OVERRIDE`(조용히 무시 금지). 중첩 객체는 통째로 교체(깊은 병합 없음).
  - 결과 `EffectiveRule`: `globalRuleVersionId`, `tenantRuleVersionId?`, 병합 body, 그리고 body에 대한 **타입 접근자**(`minCompare()`, `signerSet()`, `managerConfirmMode()`, `identityCheck(channel)`, `validations()` …). 접근자는 값을 해석만 하고 기본값을 갖지 않는다 — 키가 없으면 예외(룰 스키마가 필수로 보장).
  - 해석 결과는 `asOf`·두 버전 ID·병합 body 해시를 포함해 재현 가능하게 로그에 남길 수 있는 값객체다.

### 5. 검증 룰 실행기 (`disclosure-rules`)
- `ValidationSubject` 인터페이스: 검증이 보는 것만 노출한다 — 항목 목록(`productKey`, `insurerCode`, `groupCode`, `isRecommended`, `requestedByCustomer`, `tempProduct`, `quoteDocNo`, `fieldValues`), 스냅샷(`GradeSnapshotItem` 목록, `tieBreak`, 정책 버전 2종), 추천사유(`reasonCodes`, `reasonText`), 테넌트 플래그(`largeGa`), 서명 현황(`signedRoles`, `signDeadline`), 헤더(`groupCode`, `consultDate`), 그리고 `isInsurerOnPanel(insurerCode, date)`(Phase 2 카탈로그 전이므로 주입 함수). 워크플로 애그리게이트는 Phase 3에서 이 인터페이스를 구현한다.
- `ValidationRegistry`: `ruleId → Validation`(`(ValidationSubject, EffectiveRule, TemplateResolution) → ValidationResult{ruleId, passed, message, overridable}`). 실행기는 `EffectiveRule.validations()`에 나열된 ID만, 그 순서로 실행한다. 목록의 ID가 레지스트리에 없으면 해석 단계에서 실패(`UNKNOWN_VALIDATION`). 레지스트리에 있으나 목록에 없는 규칙은 실행되지 않는다.
- 설계서 §6.2의 12개 규칙 전부 구현. `R-RANK-MONOTONIC`은 Phase 0의 `GradeConsistencyCheck`에 구현하며 §6.3 (ii)~(v) 전부를 포함한다: 집합 일치, `tieBreak`별 순위 검사(STRICT 순열 / SHARED_RANK 경쟁 순위 + 동순위 `tie=true`), 단조성(동순위는 `gradeOrdinal` 동일), 정책 버전·`tieBreak` 허용 목록, UNAVAILABLE 항목의 등급·순위 필드 부재. 정렬·비교 API 호출이 허용되는 유일한 클래스이므로 아키텍처 규칙 허용 목록의 FQN과 일치해야 한다.
- 규칙 구현에 임계치·코드값 리터럴을 두지 않는다. 검증: 메인 소스 스캔 테스트 — `disclosure-rules` 메인 소스에 부록 D의 사유 코드 문자열(`COVERAGE`, `PREMIUM`, `PURPOSE`, `OTHER`, `CUSTOMER_REQUEST`)과 서명자 역할 문자열이 리터럴로 나타나면 실패(스키마 파일·테스트 제외). `CUSTOMER_REQUEST` 자동 부가(R-REQUESTED)는 룰 body의 `reasonCodes[].auto=true`인 코드를 읽어서 한다.

### 6. 서식 템플릿 (`disclosure-rules` + `disclosure-infra`)
- `FormTemplatePort.findActive(TenantId, templateType, LocalDate)`, `TemplateResolver`(단건, Ambiguous 실패). `TemplateResolution`은 필드 목록(`code`, `label`, `required`, `source(CATALOG|ENGINE|AGENT|SYSTEM)`, `order`, `render`)과 `layout`을 노출한다. `source`는 코드가 동작을 분기하는 닫힌 어휘이므로 enum이다. `pendingConfirmation` 항목은 필드가 아니며 `R-FIELD-REQUIRED`에 관여하지 않는다.
- `R-FIELD-REQUIRED`는 `TemplateResolution.requiredFieldCodes()`와 `ValidationSubject.fieldValues`만 본다.
- `STANDARD-v1` 번들 배포는 룰 번들과 같은 명령(`rules distribute --bundle templates/STANDARD-v1.bundle.json`).

### 7. 감사 기록 최소 구현 (`disclosure-audit`)
- `AuditPort.append(AuditEntry)`와 infra 구현: 테넌트별 `seq` 단조 증가, `entry_hash = SHA-256(prev_hash || JCS(entry))`, 첫 행의 `prev_hash`는 64개 `0`. 배포·승인·활성화·대사 실행을 기록한다(`RULE_DISTRIBUTE`, `RULE_APPROVE`, `RULE_ACTIVATE`, `RULE_RETIRE`, `RULE_RECONCILE`). 앵커·TSA·`verify`는 Phase 5.
- 동시 append는 테넌트 행 어드바이저리 락 또는 `audit_log` seq 채번 테이블로 직렬화한다(방법은 계획에 제시).

### 8. 시드와 문서
- `disclosure-demo`에 데모 테넌트 2개(`DEMO1` largeGa=true, `DEMO2` largeGa=false)와 위 번들 배포 스크립트. `DEMO1`에는 TENANT 룰 `DEMO1-HOUSE-2026`(허용 키만 덮어씀: `signDeadlineDays: 10`, `channels.PAPER_SCAN: false`) 1건.
- `docs/설계서.md`를 v1.4로 교체(첨부). 설계와 다르게 구현해야 하면 먼저 보고.

## 완료 기준 (전부 테스트로 증명)

| # | 기준 | 증명 방법 |
|---|---|---|
| C1 | **코드 diff 0 시나리오 3종**: (a) `DISC-2027-01` 배포 후 상담일 2026-12-31은 `minCompare=3`·관리자 REQUIRED, 2027-01-01은 4·OFF (b) `STANDARD-v1`에 필드 1개를 `required=true`로 추가한 번들 v2 배포 후 같은 입력이 `R-FIELD-REQUIRED` 실패로 바뀜 (c) `reasonCodes`에 코드 1개 추가 후 그 코드로 `R-REASON` 통과. 세 시나리오 모두 **프로덕션 소스 변경 없이 픽스처 데이터만 다름** — 테스트가 `git diff --stat -- '*/src/main/**'`이 비어 있음을 단언하거나, 동일 빌드 산출물로 두 데이터셋을 실행 | `RuleAsDataIT` |
| C2 | GLOBAL 0건·2건, TENANT 2건에서 해석 실패(인메모리 포트로 2건 주입); DB에서는 배타 제약이 2건을 원천 차단 | `RuleResolverTest`, `RuleVersionExclusionIT` 확장 |
| C3 | `tenantOverridable` 밖 키 덮어쓰기 → `DISALLOWED_OVERRIDE`; 안의 키 → 병합; 중첩 객체는 통째 교체; TENANT 없음 → GLOBAL 그대로 | `RuleResolverTest` |
| C4 | GLOBAL 복제본 `body`·`apply_from`·해시 UPDATE 거부, `apply_to` NULL→값 1회 허용·2회 거부, status 역방향·건너뛰기 거부, DELETE·TRUNCATE 거부; TENANT DRAFT는 수정 가능, APPROVED 이후 불변 | `RuleVersionGuardIT`(scope 2 × status 4 × 컬럼군 매트릭스) |
| C5 | 배포 멱등: 같은 번들 재실행 no-op(감사 행도 `NOOP`로 1건), 같은 ID·다른 해시 거부, `supersedes` 대상 없음 거부 | `RuleDistributionIT` |
| C6 | `supersedes` 배포가 선행 룰을 닫고, 활성화 배치가 경계일에 ACTIVE/RETIRED를 올바른 순서로 전이(Clock 주입, 2026-12-31·2027-01-01 두 날짜) | `RuleActivationIT` |
| C7 | 복제본 body를 `disclosure_migrator`로 변조 → `rules reconcile`이 `RULE_DRIFT` 플래그 생성, 정상이면 0건 | `RuleBundleReconcilerIT` |
| C8 | 12개 검증 규칙 각각 통과·실패 표본 ≥ 1, `GradeConsistencyCheck`는 STRICT·SHARED_RANK × 동점 유무 × UNAVAILABLE 포함 여부 매트릭스, 목록에 없는 ID → `UNKNOWN_VALIDATION`, 레지스트리에만 있는 규칙은 미실행 | `ValidationRegistryTest`, `GradeConsistencyCheckTest` |
| C9 | 메인 소스에 사유 코드·서명자 역할 리터럴 0건(스키마·테스트 제외) | `NoRuleLiteralsTest` |
| C10 | 템플릿 단건 해석·Ambiguous, `pendingConfirmation`이 필수 필드에 산입되지 않음 | `TemplateResolverTest` |
| C11 | 감사 행의 해시 연쇄가 연속(전 행 재계산 일치), 동시 append 50건에서 seq 중복·갭 0 | `AuditAppendIT` |
| C12 | JCS 테스트 벡터 전부 통과, `bundle_hash`가 파일·DB·재계산 삼자 일치 | `CanonicalizerTest`, `BundleHashIT` |
| C13 | `disclosure.tenant_rule_version_id`가 봉인 후 불변(Phase 0 매트릭스에 자동 편입) | `ImmutabilityTriggerIT` 갱신 |
| C14 | 선행 소과제 A·B 완료: 계약 스키마가 UNAVAILABLE 항목의 등급 필드를 거부, Envelope TODO 처리 결과, 허용 목록 FQN·폐기 항목 검사 | `ContractSchemaTest` 확장, `ArchitectureRulesTest` |
| C15 | Phase 0 전체 테스트 무손상, `net.jqwik` 0건, BOM 대조 불일치 0건(`platform-canonical` 추가분 포함) | 빌드 로그 |

## 하지 말 것

- 워크플로 애그리게이트·상태기계·유스케이스(Phase 3), 카탈로그·패널·암호화(Phase 2), 봉인·PDF(Phase 3), 서명(Phase 4), 앵커·TSA·`verify`(Phase 5), REST 컨트롤러·인가(Phase 6), 프론트(Phase 7).
- `ValidationSubject`의 구현체를 메인 소스에 두는 것(테스트 픽스처만). 애그리게이트가 Phase 3에서 구현한다.
- 규칙 ID·임계치·코드값·서식 항목명을 enum·상수로 두는 것. 예외는 `source(CATALOG|ENGINE|AGENT|SYSTEM)`와 `tieBreak(SHARED_RANK|STRICT)`처럼 코드가 동작을 분기하는 닫힌 어휘뿐이며, 그 목록은 계획에 명시하고 승인받는다.
- 기존 `V1~V3` 수정. 번들 파일에 확인되지 않은 서식 항목을 추가하는 것.

## 보고 형식

1. 태그 `phase-1`, 커밋 목록, 변경 파일 트리(신규 모듈 `platform-canonical` 포함).
2. 선행 소과제 A·B·C 결과(Envelope diff 유무, 허용 목록 FQN 목록, `no-docker` 잡 실행 여부).
3. JCS 라이브러리 선택과 테스트 벡터 결과, BOM 대조 결과.
4. 테스트 요약(모듈별·통합), 완료 기준 C1~C15 증거. C1은 세 시나리오의 픽스처 diff와 소스 diff(0)를 함께.
5. 설계서와 달리 구현했거나 해석한 지점(`form_template` 수정 가능 조건 해석 포함).
6. 다음 Phase(2 카탈로그·고객 참조, E3 엔진 API) 질문.
