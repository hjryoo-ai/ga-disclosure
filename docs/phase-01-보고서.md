# Phase 1 완료 보고 — 룰·서식 데이터

작성 2026-09-29 · 대상 지시문 `docs/phase-01-지시문.md` v1.0 · 설계서 v1.5(v1.4 + Phase 1 계획 승인 사항, `docs/phase-01-계획.md`) · PR [#1](https://github.com/hjryoo-ai/ga-disclosure/pull/1)

## 요약

- 대전제 2 "룰은 코드가 아니라 데이터다"를 네 가지로 증명했다.
  - 규제 변경(최소 비교 개수·관리자 확인 모드·서식 항목·사유 코드)은 번들 교체만으로 동작이 바뀐다. `RuleAsDataIT`가 같은 빌드로 두 데이터셋을 실행하고, 두 데이터셋의 차이가 번들 본문의 JSON 포인터 몇 개뿐임을 함께 단언한다.
  - 사규는 규제가 `tenantOverridable`로 열어 둔 키만 덮어쓸 수 있다. 그 목록과 금지 키 목록도 데이터(스키마)다.
  - 확인서는 두 버전 ID(`rule_version_id`, `tenant_rule_version_id`)로 답한다. `EffectiveRule`이 두 ID와 병합 본문 해시를 담는다.
  - 복제본이 번들과 다르면 배치가 `RULE_DRIFT` 플래그를 올린다(트리거를 끈 소유자의 변조까지).
- 테스트 **7,138건, 실패 0, 스킵 0**(Phase 0: 4,869건).
- GitHub CI: `build` 잡 통과(Phase 0 커밋 run `36549053160` — Phase 0의 C12 공백을 닫음, Phase 1 PR run `36554847957`). `no-docker` 잡 통과(§2-C) — Phase 0 심사 R6을 닫음.
- 선행 소과제 A(계약 정정)·B(허용 목록 FQN)·C(`no-docker` 잡) 완료. C는 지시문 방식이 성립하지 않아 방식을 바꿨다(§2-C, §5).

## 1. 태그·커밋·파일

- 태그: `phase-1` (보고서 커밋에 부착)
- 커밋 목록(`main..phase-1`)

| 커밋 | 요약 |
|---|---|
| `7c14503` | docs: 설계서 v1.5, Phase 1 지시문·계획 승인 기록, `db-error-codes.md`, CLAUDE.md(R2 원칙) |
| `433db53` | fix(contracts): 엔진 UNAVAILABLE `oneOf`·`tieBreak`·재조회 API, Envelope·피드를 포털 §4.1에 맞춤 (A) |
| `14bcbca` | refactor(platform): 허용 목록 FQN 열거·폐기 항목 검사, `TenantJdbcGateway`·`PlatformJdbcAutoConfiguration` 최상위 분리 (B) |
| `bf7c4aa` | feat(platform-canonical): RFC 8785 JCS·SHA-256 공유 모듈, 벡터 1,054건 |
| `6ce3873` | feat(infra): V4 — 번들 출처·해시, 룰·서식 불변 트리거(GD040~052), `tenant_rule_version_id`, 배타 제약 확장, `disclosure_operator` |
| `4331869` | feat(rules): 번들 로더, 해석기·병합, 검증 12종·레지스트리, `GradeConsistencyCheck`, 서식 해석 |
| `2f155ee` | feat(audit): 감사 해시체인, 어드바이저리 락 직렬화 |
| `0432ec2` | feat(compliance): 배포·사규 승인·활성화 배치·번들 대사 + 통합 테스트 |
| `08afd6d` | feat(app): 운영자 CLI(`cli` 프로파일), 데모 시드 |
| `4de3edc` | ci: `no-docker` 잡(초안: 컨테이너 방식) |
| `5fe0b61` | docs(readme): Phase 1, CLI, "방어선은 서로 다른 것을 본다" |
| `484a6cb` | ci(no-docker): 호스트 러너에서 Docker 데몬을 멈추는 방식으로 교체 |
| (이 커밋) | docs: Phase 1 보고서 |

- 변경 파일 트리(신규·주요 변경만)

```
platform-canonical/                         (신규 모듈, com.ga.platform:platform-canonical:0.1.0)
  src/main/java/com/ga/platform/canonical/  Canonicalizer, Sha256, CanonicalizationException
  src/test/resources/jcs/                   RFC 8785 벡터 + SOURCES.txt
platform-core/…/arch/ArchRules.java         Allowed(FQN+사유), staleClasses/stalePackages, 중첩 클래스 노출 금지
platform-spring/…/jdbc/                     TenantJdbcGateway, PlatformJdbcAutoConfiguration, TenantDirectoryReader (신규)
disclosure-domain/…/enums/                  RuleScope, TieBreak, SignOrder, TemplateType (신규)
disclosure-domain/…/grade/                  GradeSnapshot (신규), GradeSnapshotItem.unavailableReason
disclosure-rules/…/rules/
  version/   RuleVersion, RuleVersionPort
  bundle/    Bundle, RuleBundle, TemplateBundle, BundleLoader, RuleSchemas, BundleKind
  resolve/   RuleResolver, EffectiveRule, ResolutionFailure, RuleResolutionException, ReasonCodeRule
  template/  FormTemplate, FormTemplatePort, TemplateResolver, TemplateResolution, TemplateField, FieldSource, FieldScope
  validation/ ValidationSubject, Validation, ValidationResult, ValidationRegistry, standard/(12종 + StandardValidations)
  grade/     GradeConsistencyCheck (구현)
disclosure-rules/src/testFixtures/          TestSubject, TestItem, InMemory*Port, Bundles, Snapshots
disclosure-audit/…/audit/                   AuditEntry, AuditRecord, AuditAction, AuditChain, AuditPort
disclosure-compliance/…/compliance/rules/   RuleDistributionService, RuleApprovalService, RuleActivationJob, RuleBundleReconciler, 포트 5종
disclosure-infra/…/db/migration/V4__rule_bundles.sql
disclosure-infra/…/persistence/             RuleVersionRepository(재작성), FormTemplateRepository, AuditLogRepository, ComplianceFlagRepository
disclosure-infra/…/tx/TenantTransactionTemplate.java
disclosure-app/…/app/cli/OperatorCli.java, app/config/RuleGovernanceConfiguration.java, application-cli.yaml
disclosure-demo/src/main/resources/demo/phase1-seed.json, disclosure-demo/scripts/seed-phase1.sh
contracts/rules/v1/rule-bundle.schema.json (신규), rule-version.schema.json·form-template.schema.json (개정)
contracts/rules/bundles/rules/DISC-2026-07.bundle.json, DISC-2027-01.bundle.json, templates/STANDARD-v1.bundle.json
contracts/api/v1/*.openapi.yaml, contracts/events/v1/envelope.schema.json·samples (개정)
docker/postgres/init-roles.sql (disclosure_operator), .github/workflows/ci.yml (no-docker)
docs/설계서.md (v1.5), docs/phase-01-지시문.md, docs/phase-01-계획.md, docs/db-error-codes.md, README.md
```

## 2. 선행 소과제

### A. 계약 정정 — 완료

- **엔진 API**(`engine-disclosure.openapi.yaml`)
  - `GradeResultOk`·`GradeResultUnavailable` 양쪽 `additionalProperties: false`. UNAVAILABLE은 `productKey·status·reason`만 가진다.
  - 최상위 `tieBreak`(`SHARED_RANK|STRICT`, 필수).
  - `GET /internal/v1/disclosure/commission-grades/{snapshotId}` 추가(바이트 동일 재조회 약속을 설명에 명시).
  - 검증: `ContractSchemaTest`
    - `unavailableResultCarryingGradeFieldFails`: 6개 필드 각각, 값 있음·`null` 둘 다 거부.
    - `okResultMissingGradeFieldFails`: 6건.
    - `tieBreakIsRequiredAndClosed`.
    - `unavailableWithoutReasonOrWithUnknownFieldFails`.
- **Envelope: 포털 규약과 달랐다 → 포털 쪽으로 맞췄다.** `TODO(confirm-portal-4.1)` 제거.

  | Phase 0 초안 | 포털 §4.1 | 조치 |
  |---|---|---|
  | `eventType` | `type` | 이름 변경 |
  | `eventVersion`(`const 1`) | `version`(정수) | 이름 변경, `minimum 1`. payload 스키마는 `type`+`version=1`로 분기 |
  | `aggregateType` + `aggregateId`(uuid) | `aggregate{kind, id}`(id는 문자열) | 구조 변경. kind 어휘는 `DISCLOSURE`·`COMPLIANCE_FLAG`(포털 예시 `COMM_CALC`와 같은 대문자 스네이크) |
  | `tenantId`(필수) | 없음 | 제거(발행자 = 테넌트) |
  | 피드 `tenantId` 쿼리, `limit ≤ 500`, `{events, nextSeq}` | 쿼리 없음, `limit ≤ 1000`, `{events, nextSeq, headSeq, schemaVersion}` | 포털과 같게. 테넌트 범위는 서비스 토큰(인가는 Phase 6) |
  | 호환성·seq 규칙 | 규약 원문 | 스키마·OpenAPI 설명에 원문 그대로 |

  - 검증: `envelopeFollowsPortalConvention`(필드 집합 정확 일치), `eventFeedResponseFollowsPortalConvention`, 샘플 8종 통과.
- `CHECKSUMS` 재생성, `verifyContractChecksums` 통과.

### B. 아키텍처 규칙 허용 목록 — 완료

- `ArchRules.Allowed(fqn, reason)`
  - 패턴(`..`, `*`)이나 사유 없는 항목은 생성 자체가 실패한다.
  - 패키지 허용은 정확한 패키지만 포함한다(하위 패키지 불포함).
  - 허용 클래스 안의 중첩 클래스는 허용 범위지만 public·protected면 위반이다.
- `TenantScopedRepository.Gateway`는 하위 저장소(다른 패키지)의 생성자 시그니처에 쓰여 private으로 좁힐 수 없다. 그래서 **최상위 `TenantJdbcGateway`로 분리**했다. `PlatformJdbcAutoConfiguration`도 최상위로 분리했다. 허용 클래스에 public 중첩 클래스는 0개다.
- 허용 목록(`ArchitectureRulesTest`, 항목마다 사유 문자열)

  | 규칙 | FQN |
  |---|---|
  | DB 정식 경로(하위 클래스만 JDBC) | `com.ga.platform.spring.jdbc.TenantScopedRepository` |
  | 원시 JDBC 진입점 | `…jdbc.TenantJdbcGateway`, `…jdbc.TenantSessionBinder`, `…jdbc.PlatformJdbcAutoConfiguration`, `…jdbc.TenantDirectoryReader`(Phase 1 신규, §5-9) |
  | BigDecimal·BigInteger | `com.ga.disclosure.infra.json` |
  | `RatioLabel.value()` | `…seal.renderer`, `…api.mapper`, `…seal.canonical`, `…infra.persistence`, `…infra.json` |
  | 순서 API(스냅샷 항목) | `com.ga.disclosure.rules.grade.GradeConsistencyCheck` |

- 폐기 항목 검사: `allowlistsHaveNoStaleEntries`. 코드가 아직 없는 패키지(`seal.canonical`)는 `package-info`로 존재를 표시하고, javac에 `-Xpkginfo:always`를 추가했다.
- 규칙 라이브러리 자체 테스트(`ArchRulesTest`)
  - `staleAllowlistEntriesAreReported`, `allowlistEntriesMustBeExactFqnsWithReasons`.
  - 표본 `Binder$LeakyNested`(public 중첩)·`ordering.allowed.sub`(하위 패키지)가 위반으로 잡힌다.

### C. CI `no-docker` 잡 — 원격 생성 후 실행

- 원격: https://github.com/hjryoo-ai/ga-disclosure (public). `main`·`phase-0` 푸시, Phase 1은 PR #1.
- **지시문 방식이 성립하지 않았다.** GitHub Actions는 `container:` 잡에 호스트의 `/var/run/docker.sock`을 마운트한다. 첫 실행(run `36554570335`)에서 잡의 첫 단계 `test ! -S /var/run/docker.sock`이 실패했고, 로그의 `docker create … -v "/var/run/docker.sock":"/var/run/docker.sock"`으로 확인했다. 그대로 두었다면 Testcontainers가 소켓을 찾아 "Docker 없음"을 검증하지 못했을 것이다.
- **바꾼 방식:** 호스트 러너(`ubuntu-latest`)에서 `docker.socket`·`docker.service`·`containerd`를 멈추고 소켓을 지운 뒤, `docker info` 실패와 소켓 부재를 먼저 단언한다. 성공 조건은 지시문과 같다: 종료 코드 ≠ 0, 로그에 `Could not find a valid Docker environment`, 결과 XML의 스킵 0, 실패한 스위트 ≥ 1.
- 결과: **통과**(run `36554847957`, 잡 `109361430480`). 로그 발췌:
  - `Docker is unavailable`(데몬 중지 후 `docker info` 실패·소켓 부재 확인)
  - `gradle exit code: 1`, `77 tests completed, 77 failed`, `> Task :disclosure-infra:integrationTest FAILED`
  - `result files: 14, with failures/errors: 14, skipped: 0`, 로그에 `Could not find a valid Docker environment` 있음
  - 각 스위트는 `PostgresHarness` 초기화에서 실패한다(`initializationError`). 스킵 장치가 없다는 Phase 0의 코드 근거가 실측으로 확인됐다.

## 3. JCS 라이브러리와 BOM

- 채택: `io.github.erdtman:java-json-canonicalization:1.1`(Apache-2.0, 런타임 의존 0). 후보 평가는 `docs/phase-01-계획.md`에 있다.

  | 후보 | RFC §3.2 + 참조 testdata | 부록 B | ES6 숫자 표본 |
  |---|---|---|---|
  | erdtman 1.1 | 9/9 | 26/26 | 10,000,000/10,000,000 |
  | titanium-jcs 3.0.0-M4 | 9/9 | 24/26 | 999,998/1,000,000 |
  | titanium-jcs 1.1.1·2.0.0 | 9/9 | 17/26 | 10,368/1,000,000 |

- `CanonicalizerTest` 1,054건: 파일 벡터 8종(RFC §3.2.2·§3.2.3 + 참조 구현 testdata 6종, 커밋 `19d51d7` 고정), UTF-8 hex 7종, §3.2.3 정렬 순서, 부록 B 26행, ES6 1,000행, RFC MUST 거부 6종(짝 없는 서로게이트 3·중복 키·±Infinity), JsonNode 경로 동일성.
- 라이브러리 결함 보정: 짝 없는 서로게이트는 REPORT 모드 UTF-8 인코더로 거부(이슈 #5)했고, `JsonNode` 직렬화는 `WRITE_NAN_AS_STRINGS`를 해제했다. 알려진 결함 #4(1e-320 부근 서브노멀)는 이 시스템의 해시 입력(정수·문자열·불리언)과 무관하다.
- **BOM 대조:** 전 모듈 락 파일 좌표 130개를 Boot 4.1.1 BOM 트리(1,877좌표)와 전수 대조했다. BOM 관리 좌표 99개, **불일치 0건**. `platform-canonical` 추가분(Jackson 3.1.5·annotations 2.21)과 rules 메인에 들어온 networknt 전이(slf4j 2.0.18)도 BOM 버전이다. BOM 밖 신규 좌표는 `java-json-canonicalization 1.1`, `snakeyaml-engine 3.0.1`, `itu 1.14.0`(networknt 전이)이다.
- `net.jqwik`: `allDependencies` 출력에서 0건.

## 4. 테스트와 완료 기준

### 테스트 수 (`./gradlew clean build`, 전부 실행·캐시 없음)

| 모듈 / 스위트 | Phase 0 | Phase 1 |
|---|---|---|
| platform-core test | 3,056 | 3,058 |
| platform-canonical test | — | 1,054 |
| platform-spring test | 13 | 13 |
| disclosure-domain test | 1,032 | 1,032 |
| disclosure-rules test | 160 | 1,232 |
| disclosure-audit test | — | 3 |
| disclosure-app archTest | 33 | 34 |
| disclosure-infra integrationTest | 573 | 707 |
| disclosure-app integrationTest | 2 | 5 |
| **합계** | **4,869** | **7,138** (실패 0, 스킵 0) |

### 완료 기준

| # | 증거 |
|---|---|
| **C1** | `RuleAsDataIT`(4). 같은 JVM·같은 `ValidationRegistry` 인스턴스로 두 데이터셋을 실행한다. 픽스처 차이(JSON Pointer)를 단언한다. ① (a) DISC-2026-07→2027-01: `/gateRequiresManager`, `/managerConfirmMode`, `/minCompare`, `/signerSet/2`. 상담일 2026-12-31은 3·REQUIRED(3사 비교 통과, 서명 2건이면 R-SIGNER-SET 실패), 2027-01-01은 4·OFF(3사 비교 실패, 서명 2건 통과). ② (b) STANDARD v1→v2(테스트 픽스처): `/fields/9` 하나. 같은 입력이 R-FIELD-REQUIRED 실패로 바뀌고 메시지에 `TEST_ONLY_FIELD@…`가 나온다. ③ (c) reasonCodes: `/reasonCodes/5` 하나. `TEST_ONLY_REASON`이 12-31 실패, 01-01 통과. 픽스처는 `disclosure-infra/src/integrationTest/resources/rule-as-data/`에만 있고 `contracts/`에 없음을 `fixturesLiveOutsideProductionSourcesAndContracts`가 단언한다. **프로덕션 소스 diff: 시나리오 사이에 0**(한 실행 안에서 데이터만 교체). |
| **C2** | `RuleResolverTest`: `noGlobalRuleFails`, `twoGlobalRulesAreAmbiguous`, `retiredAndActiveOverlapIsAmbiguousToo`, `twoTenantRulesAreAmbiguous`(인메모리 포트로 2건 주입). `RuleVersionExclusionIT.overlappingInForceRejected`(10: ACTIVE 8 + RETIRED 2 → `23P01`) |
| **C3** | `RuleResolverTest`: `keysOutsideTenantOverridableAreRejectedNotIgnored`, `overridableKeysAreMergedAndNestedObjectsReplacedWhole`(TENANT의 `identityCheck`에 없는 채널은 병합 결과에도 없음 = 깊은 병합 아님), `noTenantRuleMeansGlobalAsIs`, `theOpenKeyListItselfIsData` |
| **C4** | `RuleVersionGuardIT`(78): INSERT(GLOBAL=APPROVED만, TENANT=DRAFT만), 본문·적용 개시일 7칸×2, 출처·해시 7칸, 식별자·scope 7칸, `apply_to` 1회 7칸, status 전이 7칸×3 목표, 승인 기록 7칸, DELETE 7칸, TRUNCATE(소유자 포함), 소유자도 트리거 적용. GLOBAL·DRAFT 칸은 존재할 수 없어 INSERT 거부로 검증 |
| **C5** | `RuleDistributionIT`(9): `rerunningTheSameBundleIsANoopWithOneNoopAuditRow`, `sameIdWithADifferentHashIsRejectedAndLeavesNothing`, `supersedesWithoutPredecessorIsRejectedAndLeavesNothing`, `eachTenantIsItsOwnTransaction` 외 |
| **C6** | `RuleActivationIT.boundaryDayRetiresThePredecessorBeforeActivatingItsSuccessor`: 고정 시계 2026-12-31·2027-01-01(Asia/Seoul). 감사 순서 RETIRE→ACTIVATE, 경계 후 과거 상담일 해석 유지. 그 외 재실행 무변경, 구간 종료 APPROVED 미활성화, 사규 승인·활성화 |
| **C7** | `RuleBundleReconcilerIT`(5): 정상이면 드리프트 0. 소유자가 트리거를 끄고 body를 바꾸면 플래그 1(감사 detail에 플래그 ID). body와 `bundle_hash`를 함께 위조해도 번들 파일 대조로 검출. 서식 변조, 알 수 없는 번들 |
| **C8** | `ValidationRegistryTest`(16): 기준 표본은 12종 전부 통과, 규칙별 실패 표본, `unknownValidationIdFailsBeforeAnythingRuns`, `registeredButUnlistedValidationsDoNotRunAndOrderFollowsData`. `GradeConsistencyCheckTest`(1,013): STRICT·SHARED_RANK × 동점 유무 × UNAVAILABLE 포함 = 8칸, 위반 표본 (i)~(iv), 시드 고정 속성 500건 ×2(`SEED=0x5EED1A01`: 유효 스냅샷 전부 통과, 1순위 서수 교란은 전부 (iii) 검출) |
| **C9** | `NoRuleLiteralsTest`: 금지어는 정본 번들 사유 코드 + `SignerRole` 값 + 부록 D 고정 목록. 스캐너 자체 음성·양성 표본 포함 |
| **C10** | `TemplateResolverTest`(4): 단건·`NO_TEMPLATE`·`AMBIGUOUS`, `pendingConfirmationIsNotAField` |
| **C11** | `AuditAppendIT`(5): DB에서 다시 읽어 전 행 재계산 일치(20건), 동시 50건 seq 1..50 중복·갭 0, 테넌트별 체인, 업무 롤백 시 감사 행도 롤백, 트리거 우회 변조 검출 |
| **C12** | `CanonicalizerTest`(1,054), `BundleLoaderTest`(22), `BundleHashIT`(2): 파일 해시 = DB `bundle_hash` = DB 본문 재계산, 그리고 JSONB 텍스트는 파일 텍스트와 다름(해시 입력이 원문이 아니라 JCS여야 하는 이유) |
| **C13** | `ImmutabilityTriggerIT`: `tenant_rule_version_id`를 본문 매트릭스에 추가(봉인 이후 6상태에서 GD001). V3 트리거는 수정하지 않았다. `everyDisclosureColumnIsCoveredByTheMatrix`: 이후 추가 컬럼(V6)도 분류를 강제 |
| **C14** | `ContractSchemaTest`(163), `ArchitectureRulesTest.allowlistsHaveNoStaleEntries`, `ArchRulesTest`(11) — §2 A·B |
| **C15** | clean build 7,138건 통과(Phase 0 테스트 무손상, 조정 내역 §5-12). `net.jqwik` 0건. BOM 불일치 0건. GitHub `build` 잡 통과(run `36554847957`) |

### 규칙 테스트 위반 주입 기록 (주입 → 실패 확인 → 제거)

| 대상 | 주입 | 결과 |
|---|---|---|
| 허용 목록(B) | 존재하지 않는 패키지 `infra.jsonx` 등재 / `infra.persistence.sub`에서 `RatioLabel.value()` 호출 / `TenantSessionBinder`에 public 중첩 클래스가 `DataSource` 사용 | `allowlistsHaveNoStaleEntries`·`ratioLabelValueOnlyInAllowlistedPackages`·`dbAccessOnlyVia…` 3건 모두 실패(`has modifier PUBLIC`) |
| platform-core 런타임 의존 0 | `implementation(libs.jcs)` | `verifyNoRuntimeDependencies` 실패(`found groups: [io.github.erdtman]`) |
| V4 트리거·제약 | 임시 V5: 룰·서식 가드 트리거 제거, 배타 제약을 ACTIVE만으로 되돌림, V3 메타 목록에 `tenant_rule_version_id` 추가 | 통합 678건 중 79건 실패(RuleVersionGuardIT 63, FormTemplateGuardIT 8, ImmutabilityTriggerIT 6 = 새 컬럼 × 봉인 6상태, RuleVersionExclusionIT 2 = RETIRED 겹침) |
| C9 | `SignerSet`에 `"MANAGER"`, `Reason`에 `'CUSTOMER_REQUEST'` 텍스트 블록 | `NoRuleLiteralsTest` 실패, 두 건 모두 보고 |
| C11 | 어드바이저리 락을 무의미한 SELECT로 교체 | 동시 50건 테스트 실패(`23505 audit_log_pkey`) |
| C6 | 활성화 루프를 퇴역 루프 앞으로 | 경계일 테스트 실패(감사 순서) |
| C7 | 번들 파일 대조 비활성 | 해시까지 위조한 변조 테스트 실패 |

모든 주입은 제거했고 제거 후 전부 통과했다.

### CLAUDE.md 규칙 9 기록

이번 Phase에서 읽은 도구·라이브러리 출력(Gradle·테스트 로그, JCS 후보 저장소의 README·이슈, GitHub Actions 로그)에 지시처럼 보이는 문장은 없었다. `net.jqwik`는 어디에도 해석되지 않았다.

## 5. 설계서와 달리 구현했거나 해석한 지점

승인된 계획 D1~D8(`docs/phase-01-계획.md`)은 설계서 v1.5에 반영했다. 그 밖의 해석과 일탈은 다음과 같다.

1. **`no-docker` 잡 방식.** 컨테이너 잡 대신 호스트 러너에서 Docker 데몬을 중지한다(§2-C). 지시문의 전제(컨테이너 잡에는 소켓이 없다)가 GitHub에서 성립하지 않는다.
2. **`form_template` 수정 가능 조건.** status가 없으므로 "미승인"을 **적용 개시 전**(`apply_from > 오늘`, `ga_today() = (now() AT TIME ZONE 'Asia/Seoul')::date`)으로 해석했다. 번들 출처 행은 항상 불변이다(`apply_to`만 1회). DB 시계를 쓰므로 테스트는 2099년·2026년 적용일로 양쪽을 검증했다(`FormTemplateGuardIT`).
3. **지시문보다 좁게 막은 것.**
   - `scope`는 TENANT·DRAFT에서도 변경 불가(TENANT 초안을 GLOBAL로 바꿔 번들 경로를 우회하지 못하게).
   - TENANT는 DRAFT로만 INSERT.
   - 승인 기록 CHECK(DRAFT면 NULL, 그 외에는 필수).
   - `apply_to` 1회 규칙은 TENANT·DRAFT에도 적용(지시문 문구 그대로).
   - `bundle_hash` 형식 CHECK, 서식 구간 겹침 배타 제약, `pending_confirmation` 배열 CHECK.
4. **활성화 배치.** APPROVED인데 적용 구간이 이미 끝난 룰(배치를 놓친 경우)은 활성화하지 않고 `expiredUnactivated`로 보고한다. 지시문 문구는 "APPROVED이고 apply_from ≤ today면 ACTIVE"지만, 그대로 하면 과거 구간을 사후에 시행 중으로 만든다.
5. **멱등성.** 이미 승인 이후 상태인 사규의 재승인은 no-op이다(배포 재실행과 같음, 감사 행 없음). GLOBAL 룰의 승인 요청은 거부한다.
6. **검증 규칙 해석.**
   - R-MIN-COMPARE는 전체 비교 항목 수(고객 요청 포함)를 센다.
   - R-REASON은 추천 항목마다 **자동 부가가 아닌** 코드 1개 이상을 요구한다(CLAUDE.md 규칙 7).
   - R-REQUESTED는 요청 항목에 `auto=true` 코드가 전부 있고, 비요청 항목에는 하나도 없어야 한다.
   - R-GRADE-REQUIRED는 UNAVAILABLE도 "결과 있음"으로 본다.
   - R-GRADE-UNAVAILABLE·R-TEMP-PRODUCT는 사유·발행번호가 있으면 오버라이드 가능, 없으면 불가다.
   - R-SIGNER-SET은 집합 밖 역할의 서명(선택적 관리자 확인)을 허용한다.
7. **`ValidationSubject` 형태.** 서명 현황은 `signedRoles` 대신 `(역할, 서명시각)` 목록이다(순서·기한 판정용). 확인서 단위 서식 값 `documentFieldValues`를 추가했고, 추천사유는 항목별이다. 구현체는 테스트 픽스처에만 있다.
8. **번들·스키마 형태.**
   - 서식 번들은 최상위에 `templateType`을 두고, bundleId는 `{templateId}.v{version}@{hash12}`다.
   - `form-template.schema.json`은 서식 **본문**(`fields·layout·pendingConfirmation`) 스키마가 됐고, `render.scope`는 필수다.
   - `identityCheck` 항목 어휘를 설계서 §5와 같은 enum으로 닫았다.
   - `contracts/rules/v1/DISC-2026-07.json`·`STANDARD-v1.json` 샘플은 번들로 대체되어 삭제했다.
9. **DB 접근 예외가 하나 늘었다.** `TenantDirectoryReader`(platform-spring, `disclosure_operator` 전용 접속)를 허용 목록에 FQN으로 등재했다(D4 승인에 따른 것). Phase 0 심사가 "유일한 허용 예외"라고 한 Phase 8 DB 헬스 지표까지 합치면 예외는 두 개가 된다. 이 클래스의 SQL(`SELECT tenant_id FROM tenant`)은 `disclosure-infra` 밖이라 SQL 스캔 대상이 아니다. `tenant`는 원래 스캔 제외 테이블이다.
10. **DISC-2027-01 데이터.** 관리자 확인 `OFF`에 맞춰 `signerSet`에서 MANAGER를 빼고(스키마 강제) `gateRequiresManager: false`로 했다. 모두 예시값이다.
11. **`EffectiveRule.identityCheck(channel)`.** 설정이 없는 채널(PAPER_SCAN·CERTIFIED_ESIGN)은 기본값 없이 예외를 던진다. Phase 4에서 채널별 필수 여부를 정해야 한다(§6 질문 4).
12. **Phase 0 테스트 조정(약화 없음).**
    - `RuleVersionExclusionIT`: ACTIVE를 직접 INSERT할 수 없게 되어, 정상 전이 경로로 재작성하고 RETIRED 겹침 2건을 추가했다.
    - `SeedData`: GLOBAL을 APPROVED+번들 출처로 넣은 뒤 ACTIVE로 전진시킨다.
    - `ContractSchemaTest`: 룰·서식 표본을 번들로 바꿨다.
    - `ArchRulesTest` 픽스처: 최상위 클래스 구조로 바꿨다.
    - `TenantRepositoryIT`·`platform-spring` 테스트: `TenantJdbcGateway`로 바꿨다.
13. **RULE_DRIFT 플래그.** `compliance_flag`에 대상 컬럼이 없어 플래그 ID와 대상(룰·서식)의 대응은 `RULE_RECONCILE` 감사 detail에 남긴다. 드리프트가 계속되면 대사 실행마다 새 플래그가 생긴다(중복 제거 정책은 Phase 6).
14. **로컬 DB.** `init-roles.sql`에 롤이 추가되어 기존 docker compose 볼륨은 `docker compose down -v`가 필요하다(README).

## 6. 다음 Phase 질문

**Phase 2 — 카탈로그·고객 참조**

1. **R-PANEL 판정 기준일.** 지금은 상담일이다. 패널 이탈이 상담일과 봉인일 사이에 일어나면 봉인 시점 기준으로 다시 볼지 정해야 한다.
2. **`birth_date_enc`(V5) 암호화 키.** 로컬·테스트는 고정 테스트 키, 운영은 KMS(테넌트별 키 권장)로 가정하려 한다. 키 식별자·회전을 컬럼에 둘지 확인이 필요하다.

**Phase 3 — 워크플로(질문을 미리 드림)**

3. **검증 단계.** R-SIGNER-SET은 "완료 불가" 규칙이라 봉인 시점에 실행하면 항상 실패한다. 규칙별 단계(봉인·완료)를 룰 데이터로 표현할지(예: `validations: [{id, stage}]`, 스키마 v2), 레지스트리 메타데이터로 둘지 정해야 한다. 데이터 쪽을 권장한다.
4. **`managerConfirmMode=OFF`와 예외 승인.** OFF여도 R-GRADE-UNAVAILABLE·R-TEMP-PRODUCT의 오버라이드는 "관리자 확인"을 요구한다(설계서 §6.2). OFF일 때 오버라이드 승인자는 누구인가? 채널별 본인확인이 비어 있는 채널(PAPER_SCAN)의 의미도 함께 정해야 한다.

**Phase E3 — 엔진**

5. **E3 착수.** 엔진 저장소 위치(`ai-comm/commission-system`, origin `ga-commission-engine`)를 확인했다. E3 지시문을 주시면 그 저장소에서 진행한다. `contracts/CHECKSUMS`를 엔진 저장소에도 두어 계약 파일 일치를 CI로 검사하는 방식을 제안한다.

**운영·공유**

6. **플랫폼 아티팩트 배포 위치.** 지금은 mavenLocal 발행 검증만 한다. 포털이 소비하려면 GitHub Packages(public)에 발행할지 정해야 한다. `platform-canonical`이 추가됐으니 포털 Phase 0 지시문 개정에 포함해야 한다.
7. **JCS 라이브러리.** erdtman은 2020년 이후 유지보수가 없다. titanium-jcs 3.0 정식판이 나오면 부록 B를 다시 검증하고 교체를 검토할지 확인이 필요하다(교체해도 `Canonicalizer` 뒤라 호출부 변경은 없다).
8. **사규 초안 작성 경로.** 지금은 `demo seed`만 DRAFT를 만든다. Phase 6 API 전에 `rules draft` CLI가 필요한가?
