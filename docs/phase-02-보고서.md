# Phase 2 완료 보고 — 카탈로그·패널·고객 참조

작성 2026-09-30 · 대상 지시문 `docs/phase-02-지시문.md` · 계획 `docs/phase-02-계획.md`(2026-09-29 "권장승인") · 설계서 v1.6(v1.5 + Phase 1 수용 심사 §3 반영 + Phase 2) · 브랜치 `work/phase-2`

## 요약

- **카탈로그**는 파일 수입으로만 들어온다.
  - 수입은 행 단위 upsert다. 파일에서 빠진 행은 삭제하지 않고 유효기간 `[from, to)`를 닫는다.
  - 같은 파일(kind·SHA-256)은 NOOP이고, 이전보다 이른 `asOf`는 거부한다.
  - 행 삭제와 TRUNCATE는 DB가 거부한다(GD070).
  - 조회는 전부 기준일이 필수다. `R-PANEL`은 수입된 패널로 상담일 시점을 판정한다.
- **고객 참조 암호화**
  - 이름·연락처·생년월일은 AES-256-GCM 암호문으로만 저장한다.
  - AAD는 테넌트·테이블·컬럼·고객·키 ID를 JCS로 묶는다. 암호문을 다른 곳으로 옮기면 복호화에 실패한다.
  - 테넌트별 DEK를 KEK로 감싸 보관하고, 순환 → 재암호화 → 파기 순서로 키를 바꾼다. 쓰이고 있는 키는 파기하면 DB가 거부한다.
  - 도메인에서는 값이 `Sensitive<T>`로만 다닌다. 원문을 꺼내는 `reveal`은 호출할 수 있는 패키지를 ArchUnit으로 제한했다.
- **평문 누출 0건**
  - 모든 테스트 결과 XML(49개 파일, 센티널 14종)에서 0건.
  - `PlaintextLeakScanIT`가 TRACE 로그, 표준 출력·오류, 예외, `toString`, 감사, DB 덤프, PostgreSQL 서버 로그를 검사해 0건.
- **Phase 1 심사 이월 사항**을 모두 반영했다.
  - 검증 단계를 데이터로 옮겼다(`ValidationStage`).
  - `exceptionApproval`을 관리자 서명과 분리했다.
  - `RULE_ACTIVATION_MISSED` 플래그를 추가했다.
  - no-op 재승인에도 감사 행을 남긴다.
  - 직접 DB 접근 허용 목록 규약을 설계서와 CLAUDE.md에 넣었다.
  - 플랫폼 아티팩트를 GitHub Packages로 발행한다.
- **테스트 7,281건, 실패 0, 스킵 0**(Phase 1: 7,138건).
  - 규칙 테스트 위반 주입 12건은 모두 잡혔고, 제거한 뒤 전부 통과했다.
- **플랫폼 0.1.0 발행:** `platform-v0.1.0` 태그로 GitHub Packages에 발행했다(publish-platform run `36700521819` 성공). 발행 직후 mavenLocal 없이 GitHub Packages만으로 소비 빌드가 통과했다.
- **계약 PR #2**(엔진 오류 응답 401/403/422)는 merge commit `440a009`로 `main`에 병합했고, 이 브랜치에 반영했다(`f23cd4f`). 계약 파일을 먼저 바꾼 쪽은 이 저장소다.

## 1. 태그·커밋·파일

- 태그 `platform-v0.1.0`은 `db55ad7`에 있다. 태그 `phase-2`는 보고서 커밋에 붙인다.
- 커밋 목록(`main..work/phase-2`)

| 커밋 | 요약 |
|---|---|
| `3adcfa7` | docs: Phase 1 수용 심사, Phase 2 지시문·계획 |
| `f23cd4f` | merge: `origin/main`(계약 PR #2, engine-disclosure 1.1.0) |
| `db55ad7` | build(platform): GitHub Packages 발행, `checkPlatformVersion`, `verifyPublishedPlatformFromGitHub`, `publish-platform.yml`(선행 C) |
| `5f47546` | feat: Phase 2 본체 + Phase 1 심사 항목 + 설계서 v1.5/v1.6, CLAUDE.md, `db-error-codes.md`, README |
| (이 커밋) | docs: Phase 2 보고서 |

설계서와 코드 변경을 같은 커밋에 넣었다(CLAUDE.md 규칙 8). 선행 C(`db55ad7`)는 설계서 §11보다 한 커밋 앞서지만, 태그를 붙일 커밋에 발행 코드만 두려고 이렇게 나눴다.

## 2. 선행 소과제

### A. 검증 단계를 데이터로(심사 §3-1) — 완료

- `validations`는 이제 `[{id, stages}]` 형식이다. 단계 값은 닫힌 enum `ValidationStage {COMPARE, GRADE, REASON, SEAL, COMPLETE}` 중에서 고른다.
- 실행 방식
  - `ValidationRegistry.plan(stage, rule)`과 `run(stage, …)`는 요청한 단계의 규칙만, 데이터에 적힌 순서대로 실행한다.
  - `stages`가 비었거나 ID가 중복되면 해석이 실패한다(`MissingRuleKeyException`).
  - 레지스트리에 없는 ID가 있으면 어느 단계에서든 `UNKNOWN_VALIDATION`으로 실패한다.
- 단계 배정(계획 §7-1)

  | 단계 | 규칙 |
  |---|---|
  | COMPARE+SEAL | MIN-COMPARE, DISTINCT-INSURER, SAME-GROUP, PANEL, TEMP-PRODUCT |
  | GRADE+SEAL | GRADE-REQUIRED, GRADE-UNAVAILABLE, RANK-MONOTONIC |
  | REASON+SEAL | REASON, REQUESTED, FIELD-REQUIRED |
  | COMPLETE | SIGNER-SET |

- 번들 `DISC-2026-07`·`DISC-2027-01`과 IT 픽스처 `DISC-TEST-REASON`은 제자리에서 수정했다. 번들 ID는 다음과 같이 바뀌었다.

  | 번들 | 새 ID |
  |---|---|
  | DISC-2026-07 | `@a1f0c6caace8` |
  | DISC-2027-01 | `@80b308dcdb11` |
  | DISC-TEST-REASON | `@d01c54336450` |

- 제자리 수정은 첫 운영 배포 전에만 허용한다(설계서 §5 주석). 이를 강제하려고 `contracts/rules/released-bundles.txt`(현재 비어 있음)와 `ReleasedBundlesAreFrozenTest`를 추가했다. 이 테스트는 목록에 올라간 번들이 사라지거나 본문 해시가 바뀌면 실패한다. 계획 §7-2의 제안이다.
- C1 시나리오(`RuleAsDataIT`, 4건)는 그대로 통과한다.
- `exceptionApproval {role}`을 추가했다(심사 §3-2).
  - 스키마 필수 항목이며, `tenantOverridable`에 넣으면 스키마가 거부한다.
  - `EffectiveRule.exceptionApprovalRole()`이 이 값을 돌려준다.
  - `managerConfirmMode=OFF`여도 예외 승인은 `MANAGER`가 한다(`RuleResolverTest.managerConfirmOffStillRequiresManagerExceptionApproval`).

### B. `RULE_ACTIVATION_MISSED`, no-op 감사 — 완료

- V5에서 `compliance_flag`에 `target_kind`·`target_id` 컬럼과, 열린 플래그를 대상별로 하나만 허용하는 유일 인덱스를 추가했다.
- `ComplianceFlagPort.raiseOpen`은 `ON CONFLICT DO NOTHING`으로 동작한다. 같은 대상에 열린 플래그가 있으면 새로 만들지 않고 그 플래그의 ID를 돌려준다.
- 활성화 배치는 적용 구간이 이미 끝난 APPROVED 룰마다 `RULE_ACTIVATION_MISSED`(HIGH) 플래그를 올린다. 감사 `FLAG_RAISE`는 새로 만들 때만 남는다. CLI는 `MISSED` 줄을 출력한다.
- `RULE_DRIFT`도 같은 `raiseOpen`으로 바꿔 대사를 다시 돌려도 플래그가 복제되지 않는다(계획 §7-3, Phase 1 동작 변경).
- 이미 승인된 사규를 다시 승인하면 감사 1행(`{outcome: NOOP, status}`)을 남긴다(심사 D4). 이 때문에 Phase 1 테스트가 "감사 0행"에서 "NOOP 1행"으로 바뀌었다. 테스트를 약화한 것이 아니라 더 엄격하게 만든 것이다.

### C. 플랫폼 아티팩트 발행 — 완료

- 루트 빌드
  - 세 플랫폼 모듈에 `GitHubPackages` 저장소를 추가했다. `GITHUB_ACTOR`와 `GITHUB_TOKEN` 환경변수가 있을 때만 구성된다.
  - `checkPlatformVersion`은 태그 버전과 세 모듈의 버전이 모두 같은지 검사한다.
  - `verifyPublishedPlatformFromGitHub`는 소비자 빌드를 `-Pga.platformRepo=github`로 실행한다. `com.ga.platform` 그룹을 GitHub Packages 한 곳에서만 받고(`exclusiveContent`), `platform-core` jar가 `~/.m2`가 아니라 Gradle 캐시에서 왔는지 확인한다.
- 워크플로 `publish-platform.yml`: `platform-v*` 태그에서 실행되고 `packages: write` 권한을 가진다. 단계는 다음 순서다.
  1. 버전 검사
  2. jqwik 의존 검사
  3. 세 모듈 `check`
  4. 발행
  5. `~/.m2/repository/com/ga`를 지운 뒤 GitHub Packages만으로 소비 검증
- 실행 로그(run `36700521819`, 모든 단계 성공) 발췌:
  ```
  platform modules all at 0.1.0: [platform-core, platform-canonical, platform-spring]
  > Task :platform-core:publishPlatformCorePublicationToGitHubPackagesRepository
  > Task :platform-canonical:publishPlatformCanonicalPublicationToGitHubPackagesRepository
  > Task :platform-spring:publishPlatformSpringPublicationToGitHubPackagesRepository
  compiling disclosure-domain against [/home/runner/.gradle/caches/modules-2/files-2.1/com.ga.platform/platform-core/0.1.0/…/platform-core-0.1.0.jar] (from GitHub Packages)
  platform-spring resolves from GitHub Packages with 15 artifacts
  platform-canonical resolves from GitHub Packages with 5 artifacts: [jackson-annotations-2.21.jar, jackson-core-3.1.5.jar, jackson-databind-3.1.5.jar, java-json-canonicalization-1.1.jar, platform-canonical-0.1.0.jar]
  ```
- 로컬 mavenLocal 경로(`verifyPublishedPlatform`)도 그대로 통과한다. README에 두 가지 소비 경로와 소비 측 `repositories {}` 예시를 적었다.

## 3. 암호화 방식·키 계층 요약

| 항목 | 구현 |
|---|---|
| 알고리즘 | AES-256-GCM(JCA `AES/GCM/NoPadding`), `SecureRandom` 96비트 nonce, 128비트 태그. 추가 라이브러리 없음 |
| 저장 형식 | `0x01 ‖ nonce(12) ‖ ciphertext‖tag(16)`. DB CHECK가 첫 바이트와 최소 길이 29를 검사하므로, 평문 바이트를 넣으면 `23514`로 거부된다 |
| AAD | JCS `{column, customerRef, keyId, table:"customer_ref", tenantId, v:1}`. 다른 행·컬럼·테넌트로 옮기거나 `enc_key_id`를 바꾸면 복호화에 실패한다(`CiphertextRejectedException`) |
| 평문 정규화 | 이름은 NFC·trim에 제어문자 거부, 1–100자. 전화는 숫자만 받고 `01[016789]` + 7–8자리. 생년월일은 `yyyy-MM-dd`. 오류 메시지에 입력값을 넣지 않는다 |
| DEK | 테넌트별 256비트 키. `customer_data_key`에는 KEK로 감싼 형태로만 저장한다. 감싸기 AAD는 `{kekId, keyId, tenantId, v:1}`. 테넌트당 ACTIVE 키는 하나(부분 유일 인덱스)이고, 첫 등록 때 만든다 |
| KEK | `KeyProviderPort`(`currentKekId`·`wrap`·`unwrap`). 개발·테스트용 `LocalFileKeyProvider`는 저장소 밖 JSON 파일을 쓰고, 권한이 600이 아니면 거부하며, 기존 파일을 덮어쓰지 않는다. 설정은 `ga.crypto.local-kek-file` / `GA_LOCAL_KEK_FILE`, 생성은 CLI `crypto init-kek`. 운영 KMS 어댑터는 이후 Phase에서 구현을 교체한다 |
| 순환 | `customer rekey`: ① 한 트랜잭션에서 새 키 ACTIVE, 구 키 RETIRED ② 구 키로 암호화된 행을 배치 단위로 재암호화 ③ 남은 행이 0이면 구 키 DESTROYED(`wrapped_key` NULL). 중단되면 재실행이 이어받는다. 감사는 `CUSTOMER_KEY_ROTATE`·`CUSTOMER_REKEY`·`CUSTOMER_KEY_DESTROY` |
| DB 방어 | GD060(키 식별자·KEK·감싼 키 불변, 상태는 한 단계 전진만), GD061(쓰이는 키 파기 금지), GD062(키 삭제 금지), GD063(고객 식별자 불변), GD064(고객 행 삭제·TRUNCATE 금지), FK `23503`(없는 키 ID) |
| 코드 경계 | JCA는 `infra.crypto`에서만 쓴다. `Sensitive.reveal` 호출은 `domain.pii`(생년월일 대조)·`infra.crypto`·`rules.pii`(`MaskedView`)에서만 허용한다. `Sensitive`를 컴포넌트로 가진 record와, `domain.pii` 밖에서 원문 PII 타입을 필드로 쓰는 것을 금지한다. `BirthDate.matches`는 `MessageDigest.isEqual`만 쓴다. 모두 ArchUnit으로 검사한다 |
| 직렬화 | `Sensitive`는 `Serializable`이 아니고 게터도 없다. 앱의 JSON 매퍼에는 `SensitiveGuardModule`을 붙여 `Sensitive`와 PII 값 타입의 직렬화를 거부한다 |
| 고객 ID | `CR-` + 무작위 UUID 16진 32자(CHECK). PII의 해시가 아니다 |
| 마스킹 | 룰 데이터 `masking.{name,phone,birthDate}.{keepFirst,keepLast,maskChar}`. 기본값은 이름 1/1, 전화 3/4, 생년월일 0/0이다. 코드 포인트 단위로 적용하고, 가릴 글자가 없으면 전부 가린다. 테넌트가 덮어쓸 수 있다. `TODO(confirm#12)` |

## 4. 카탈로그 파일 스키마

`contracts/catalog/v1/catalog-file.schema.json`(JSON Schema 2020-12, `CHECKSUMS`에 포함).

- 최상위 필드: `schemaVersion: 1`, `kind ∈ {PRODUCT_GROUPS, PRODUCTS, INSURER_PANEL}`, `source`(`^[A-Z][A-Z0-9_]{0,63}$`), `asOf`(필수, 시스템이 오늘 날짜로 채우지 않음).
- `kind`가 본문 배열을 하나로 정한다: `groups` / `products` / `insurers`. 다른 배열이 함께 있으면 스키마 위반이다.
- 배열 항목
  - `groups[]`: `{groupCode, name, line, applyFrom, applyTo|null}`
  - `insurers[]`: `{insurerCode, insurerName, line, activeFrom, activeTo|null}`
  - `products[]`: `{productKey "{insurer}:{code}", insurerCode, groupCode, productName, saleFrom, saleTo|null, defaults{…}}`. `productKey`의 접두가 `insurerCode`와 같은지는 수입기가 검사한다. 금액은 정수 원만 받는다.
- 상품군 코드 목록은 두지 않는다. 형식만 검사한다(§14 #1).
- 수입 규칙
  - 파일의 행은 upsert하고, 파일에 없는 열린 행은 `asOf`로 닫는다.
  - 상품 수입은 상품군이 먼저 들어와 있어야 한다(FK).
  - 한 번의 수입은 `catalog_import` 1행과 감사 `CATALOG_IMPORT` 1행을 남긴다. 감사 detail에는 행별 `changes`(before/after/end)가 들어간다.
- 데모 파일 `disclosure-demo/src/main/resources/demo/catalog/*.json`에는 가상 상품군 2개, 보험사 6개, 상품 9개가 있다. 모든 코드는 가상이다.

## 5. 테스트와 완료 기준

### 테스트 수 (`./gradlew clean build --no-build-cache`, 전부 실행)

| 모듈 / 스위트 | Phase 1 | Phase 2 |
|---|---|---|
| platform-core test | 3,058 | 3,058 |
| platform-canonical test | 1,054 | 1,054 |
| platform-spring test | 13 | 13 |
| disclosure-domain test | 1,032 | 1,056 |
| disclosure-rules test | 1,232 | 1,252 |
| disclosure-audit test | 3 | 3 |
| disclosure-app archTest | 34 | 36 |
| disclosure-infra integrationTest | 707 | 802 |
| disclosure-app integrationTest | 5 | 7 |
| **합계** | **7,138** | **7,281** (실패 0, 스킵 0) |

`scanPlaintextLeaks: 49 result files, 14 forbidden strings, 0 hits`. 결과 XML 49개는 통합 테스트 클래스 19개를 포함한 전체 스위트의 수와 같다.

### 완료 기준

| # | 증거 |
|---|---|
| **P1** | `CatalogAsOfIT`(19) — 반개구간과 경계일: `productSaleWindowIsHalfOpen`, `groupWindowIsHalfOpen`, `panelWindowsAreHalfOpenAndMayResume`(각각 `from-1`/`from`/`to-1`/`to`), `emptyWindowIsNeverVisible` |
| | `CatalogAsOfIT` — 겹침과 조회 조건: `overlappingPanelWindowsAreRejectedByTheDatabase`(23P01), `asOfIsMandatoryAndTheBoundTenantMustMatch`, `searchFiltersByInsurerAndKeywordWithLikeCharactersEscaped`(`%`·`_` 이스케이프 음성 표본 포함) |
| | `PanelValidationIT` — `R-PANEL`이 수입된 패널로 상담일 시점을 판정한다 |
| **P2** | `CatalogImportIT`(25) — 수입 동작: `sameFileAgainIsANoopRecordedInTheAuditLog`, `missingProductsAreClosedAtTheImportDateNotDeleted`, `everyRowCarriesSourceFileHashAndSyncTime`, `anOlderFileCannotRollTheCacheBack`, `productsNeedTheirGroupFirst`, `invalidFilesAreRejectedBeforeAnythingIsWritten` |
| | `CatalogImportIT` — DB 방어: `databaseRefusesDeletionAndIdentityChanges`(GD070·GD071·GD030), `ownerCannotTruncateCatalogTables`(소유자 TRUNCATE CASCADE) |
| **P3** | `CustomerEncryptionIT`(19) — 왕복과 이식: `roundTripKeepsValuesAndStoresOnlyCiphertext`, `ciphertextMovedToAnotherRowFailsToDecrypt`, `…AnotherColumn…`, `…AnotherTenant…` |
| | `CustomerEncryptionIT` — 키 순환: `rotationDecryptsOldAndNewKeysMeanwhileAndLeavesOnlyReencryptedRows`(순환 도중 구·신 키가 섞여도 복호화, 완료 후 구 키 DESTROYED, 모든 행이 신 키), `anotherMasterKeyCannotUnwrapTheDataKeys`, `keyFileReadableByOthersIsRefused` |
| | `CustomerEncryptionIT` — DB 방어: `keyStoreAndCustomerRowsAreGuardedByTheDatabase`(GD060·062·063·064, 평문 23514, FK 23503), `ownerCannotTruncateKeyStoreOrCustomers` |
| | `CustomerEncryptionIT` — 연락처: `phoneIsReleasedOnlyForNotificationAndTheReadIsAudited` |
| | `OperatorCliIT.catalogImportKekInitAndCustomerRekey` — CLI 경로 |
| **P4** | `PlaintextLeakScanIT`(2): 센티널 고객을 모든 경로로 다룬 뒤 아래 출력에서 원문·변형·UTF-8 16진이 0건임을 확인한다. 검사 경로 목록은 §6이다 |
| | `theScannerFindsEveryFormOfTheSentinels`: 스캐너가 실제로 찾아내는지 보는 대조 검사 |
| | 루트 태스크 `scanPlaintextLeaks`: 모든 테스트 결과 XML 검사 |
| **P5** | `ArchitectureRulesTest.sensitiveValuesDoNotLeakThroughRecordsFieldsOrCrypto`: record 컴포넌트 금지, 원문 필드 금지, `reveal` 호출처, `javax.crypto` 사용처 |
| | `SensitiveTest.javaSerializationIsImpossible` |
| | `DisclosureApplicationIT.applicationJsonMapperRefusesPersonalData`: 앱 컨텍스트의 JSON 매퍼 |
| | `PlaintextLeakScanIT`: 가드 매퍼와 기본 매퍼 모두 직렬화 실패, 메시지에 값 없음 |
| | 위반 주입 I1·I2·I3·I5(§5 아래) |
| **P6** | `IdentityMatchTest`(`matchingInputsInEitherFormat`, `everythingElseIsFalseWithoutThrowing`, `nullInputIsFalse`, `matchingWritesNothingToStandardStreams`) |
| | `ArchitectureRulesTest.birthDateMatchUsesConstantTimeComparison`(`MessageDigest.isEqual`을 호출하고 `Arrays.equals`·`String.equals`는 쓰지 않음, 주입 I4) |
| | P4의 대조 경로(맞는 입력, 틀린 입력, 형식 오류 입력) |
| **P7** | `RlsIsolationIT`(125): 테이블 목록을 `pg_class`에서 읽어 20개로 고정하고, 새 테이블 `customer_data_key`·`catalog_import`가 자동으로 모든 칸에 들어간다. `tenant_id`가 첫 컬럼인지도 확인한다 |
| | `TenantPredicateScanTest`: 20개 테이블 SQL 스캔. 새 저장소 `CatalogRepository`·`CustomerVaultRepository`가 스캔 대상이다 |
| | 주입 I9 |
| **P8** | A: `RuleAsDataIT`(4, C1 그대로), `ValidationRegistryTest`(`stagesFollowTheRuleData`, `onlyTheRequestedStageRuns`, `sealDoesNotRunSignerSetSoAnUnsignedDocumentCanBeSealed`, `emptyStagesDuplicateIdsAndUnknownStagesAreRejected`), `ContractSchemaTest`(`validationStepsHaveExplicitClosedStages`, `exceptionApprovalIsClosedAndCannotBeOpenedToTenants`), `RuleResolverTest`(`exceptionApprovalCannotBeOverriddenButMaskingCan`, `managerConfirmOffStillRequiresManagerExceptionApproval`), `ReleasedBundlesAreFrozenTest` |
| | B: `RuleActivationIT.approvedRuleWhoseWindowAlreadyEndedRaisesActivationMissedOnce`(재실행해도 플래그·감사가 1건), `runningTheBatchAgainOnTheSameDayChangesNothing`, `houseRuleIsApprovedOnlyWithOpenKeysThenActivated`(NOOP 감사 1행), `RuleBundleReconcilerIT` |
| | C: publish-platform run `36700521819` |
| **P9** | clean build 7,281건 통과. Phase 0·1 테스트는 약화 없음(바뀐 것은 §7-8 한 건으로, 더 엄격해진 방향) |
| | `allDependencies` 8,114줄에서 `net.jqwik` 0건 |
| | BOM: `main` 대비 락 파일의 새 좌표는 `ch.qos.logback:logback-classic`·`logback-core` 1.5.38 두 개뿐이고, Boot 4.1.1 BOM의 `logback.version` 1.5.38과 같다. 그 외 좌표는 기존 좌표가 새 구성(설정)에 추가된 것이며, Phase 1에서 전수 대조한 버전 그대로다. 불일치 0건 |

### 규칙 테스트 위반 주입 기록 (주입 → 실패 확인 → 제거)

스크립트로 하나씩 넣고 해당 테스트만 실행한 뒤 되돌렸다. 마지막에 `git status`가 깨끗한 것을 확인했다.

| # | 주입 | 결과 |
|---|---|---|
| I1 | `workflow.customer`에 `record InjectedPiiRecord(Sensitive<CustomerName> name)` | `sensitiveValuesDoNotLeak…` 실패: "no record has a component of type Sensitive" |
| I2 | `workflow.customer`에 `CustomerName raw` 필드 | 같은 테스트 실패: "raw PII value types are fields only inside …domain.pii" |
| I3 | `infra.persistence`에서 `name.reveal(…)` 호출 | 같은 테스트 실패: "Sensitive.reveal() may be used only in [domain.pii, infra.crypto, rules.pii]" |
| I4 | `BirthDate.matches`의 `MessageDigest.isEqual`을 `Arrays.equals`로 교체 | `birthDateMatchUsesConstantTimeComparison` 실패(위반 2건: isEqual 미호출, 단락 비교) |
| I5 | `infra.persistence`에서 `javax.crypto.Cipher.getInstance` 호출 | `sensitiveValuesDoNotLeak…` 실패: "javax.crypto only in …infra.crypto" |
| I6 | `Customer.toString`이 이름을 `reveal`해 출력 | `PlaintextLeakScanIT.noPlaintext…` 실패 |
| I7 | `CustomerFieldCipher.encrypt`가 평문을 `System.err`로 출력 | `PlaintextLeakScanIT` 실패, `scanPlaintextLeaks`도 결과 XML에서 검출(`TEST-…PlaintextLeakScanIT.xml: 갸냐댜…`) |
| I8 | AAD에서 `customerRef` 제거 | `CustomerEncryptionIT.ciphertextMovedToAnotherRowFailsToDecrypt` 실패 |
| I9 | `V99`: RLS 없는 `injected_probe(tenant_id, note)` | `RlsIsolationIT` 4건 실패(테이블 수 고정, 강제 RLS·정책, 행 격리, 정책 목록) |
| I10 | V5에서 카탈로그 삭제·TRUNCATE 트리거 생성 제거 | `CatalogImportIT` 6건 실패(DELETE 3, TRUNCATE 3) |
| I11 | `RuleApprovalService`의 NOOP 감사 기록 제거 | `RuleActivationIT.houseRuleIsApprovedOnlyWithOpenKeysThenActivated` 실패 |
| I12 | `raiseOpen`에서 `ON CONFLICT … DO NOTHING` 제거(항상 INSERT) | `RuleActivationIT` 2건과 `RuleBundleReconcilerIT.bodyTamperedByTheOwnerIsFlagged` 실패 |

모든 주입을 제거했고, 제거한 뒤의 clean build는 전부 통과했다.

### CLAUDE.md 규칙 9 기록

이번 Phase에서 읽은 도구 출력에 지시처럼 보이는 문장은 없었다. 읽은 것은 Gradle·테스트 로그, GitHub Actions 로그, PostgreSQL 서버 로그, 엔진 저장소 E3 에이전트의 보고다. `net.jqwik`는 어디에서도 해석되지 않았다.

## 6. 로그 스캔이 검사한 출력 경로

센티널 값은 `pii-sentinels.properties`에 있다. 가상의 이름·전화·생년월일과 각각의 변형(하이픈, 뒷자리, `yyyyMMdd`, 부분 문자열)이 있고, 이름의 UTF-8 16진 표현까지 모두 14종이다.

1. logback 모든 로거의 TRACE 로그. Spring JDBC 바인드 값 로그를 포함하며, 메모리 어펜더로 모은다. JDBC 활동이 실제로 잡혔는지도 단언한다.
2. 표준 출력과 표준 오류(tee로 가로챔).
3. 일부러 일으킨 실패의 예외 메시지와 스택 트레이스: 형식 오류 4종, 다른 테넌트, 없는 연락처, 직렬화 시도 3종.
4. 반환 객체의 `toString()` 전부: `NewCustomer`, `Customer`, `Sensitive`, 대조 결과.
5. 감사 로그의 해당 테넌트 전 행.
6. 컨테이너 안에서 실행한 `pg_dump --data-only`(모든 테넌트). `bytea`가 16진으로 덤프되므로 16진 센티널로도 검사한다.
7. PostgreSQL 서버 로그(`docker logs`).
8. 빌드 전체의 테스트 결과 XML(`scanPlaintextLeaks`, 모든 `Test` 태스크의 finalizer이고 `check`가 의존). 여기에는 모든 스위트의 system-out·system-err와 실패 메시지가 들어 있다. 도메인 단위 테스트는 센티널이 아닌 값을 써서, 이 스캔이 거짓 양성을 내지 않게 했다.

## 7. 설계서와 달리 구현했거나 해석한 지점

1. **`RULE_DRIFT`에도 열린 플래그 중복 제거를 적용했다.** 계획 §7-3에서 승인됐다. Phase 1 동작이 바뀌어, 대사를 다시 돌려도 플래그가 하나로 유지된다.
2. **마스킹을 §14 #12로 새로 넣었다.** 지시문의 "§14 #8"은 이 설계서에서 동의 문구 항목이라, 마스킹은 새 번호를 받았다(계획 §0).
3. **카탈로그 변경 이력은 감사 로그에만 남는다.** 테이블은 현재 값만 가진다. 이력 테이블은 두지 않았다(계획 §7-5).
4. **첫 DEK 생성 경합이 있다.** 한 테넌트의 첫 고객 등록 두 건이 동시에 들어오면 둘 다 DEK를 만들려 한다. 부분 유일 인덱스 때문에 한쪽은 `23505`로 실패하고, 다시 시도하면 성공한다. 데이터는 깨지지 않는다(키는 하나, 고객 행은 롤백). Phase 3 등록 API에서 재시도나 advisory lock으로 흡수하겠다.
5. **`customer_ref` 삭제 금지(GD064)는 계획 §3에 있었지만 V5 초안에서 빠져 있었다.** 이번에 V5에 넣었다. V5는 아직 어디에도 배포되지 않은 이번 Phase의 새 마이그레이션이다. 파기는 Phase 5 보존기간 배치가 이 규칙을 대체하는 마이그레이션과 함께 도입한다.
6. **계획에 적은 테스트 클래스 이름 두 개는 만들지 않았다.** 해당 검증은 다른 곳에 있다.
   - `SensitiveSerializationTest`의 검증은 `SensitiveTest.javaSerializationIsImpossible`, `DisclosureApplicationIT.applicationJsonMapperRefusesPersonalData`, `PlaintextLeakScanIT`로 나뉘어 있다.
   - `RuleApprovalNoopAuditIT`의 검증은 `RuleActivationIT.houseRuleIsApprovedOnlyWithOpenKeysThenActivated`에 있다.
7. **데모 고객 등록은 구현하지 않았다.** 계획 §5에는 `seed.sh`에 넣는다고 적었다. 등록하려면 운영자 CLI에 고객 PII를 인자로 받는 명령을 새로 둬야 하는데, 셸 이력·프로세스 목록에 평문이 남는 경로가 생긴다. 그래서 Phase 3 등록 API로 넘기길 제안한다. `seed.sh`는 로컬 KEK만 준비하며, 두 번 실행해도 같은 결과임을 확인했다(§8).
8. **Phase 1 테스트 한 건의 기대값을 바꿨다.** 재승인 no-op이 감사 0행에서 NOOP 1행이 됐다(심사 D4). 더 엄격해진 방향이다.
9. **`MaskingRule`은 남길 글자 수가 값의 길이 이상이면 값 전체를 가린다.** 짧은 값이 통째로 보이는 것을 막으려는 해석이다(`maskingKeepsOnlyTheConfiguredEndsAndNeverRevealsAShortValueWhole`).
10. **선행 C의 소비 검증은 `exclusiveContent`와 jar 경로 검사를 함께 쓴다.** `exclusiveContent`로 `com.ga.platform` 그룹을 GitHub Packages 한 곳에서만 받게 하고, jar 경로(`~/.m2`가 아님)를 이중으로 확인한다. 워크플로는 추가로 `~/.m2/repository/com/ga`를 지운다.

## 8. 데모

`docker compose up -d postgres` 후 `disclosure-demo/scripts/seed.sh 2026-09-30`을 두 번 실행했다. KEK 파일은 세션 임시 디렉터리를 썼다. 결과는 아래 표와 같다.

| 실행 | 결과 (종료 코드 0) |
|---|---|
| 1회차 | 테넌트 `DEMO1`·`DEMO2` 생성. 번들 3종 배포(`DISC-2026-07@a1f0c6caace8`, `DISC-2027-01@80b308dcdb11`, `STANDARD.v1@f850a2f9b53e`). 사규 승인 후 활성화(`DEMO1-HOUSE-2026`, `DISC-2026-07`). 대사 drift 0. 카탈로그 테넌트당 상품군 2·패널 6·상품 9 `IMPORTED`. `KEK_INIT KEK-LOCAL-1` |
| 2회차 | 모든 단계가 무변경: `EXISTS`, 배포 `NOOP`, 승인 `ALREADY ACTIVE`(NOOP 감사), 활성화 `[]`, drift 0, 카탈로그 6건 모두 `NOOP`(같은 import ID) |

## 9. 다음 Phase(3 워크플로 코어·봉인) 질문

1. **상품 키 길이.** 계약(`engine-disclosure.openapi.yaml`)과 카탈로그 스키마는 `productKey`를 최대 129자(`{64}:{64}`)까지 허용한다. 엔진 저장소의 상품 키는 40자 제한이다. 계약을 40자로 좁힐지, 엔진을 넓힐지 정해야 한다. 지금은 데모 키가 짧아 문제가 없지만, 카탈로그로 긴 키가 들어오면 엔진 요청이 실패한다. 계약 변경이라면 이 저장소가 먼저 PR을 내겠다.
2. **엔진 `contracts/UPSTREAM` 고정 대상.** E3는 PR #2의 head인 `af3399c`를 고정했다. 이제 PR #2가 `440a009`로 병합됐으므로 엔진 PR에서 merge commit으로 바꿀지 정해야 한다. 파일 내용은 같다.
3. **E3가 요청한 계약 후속 작업**(엔진 보고서 §6): `INVALID_POLICY` 코드, GET 500, 400 코드 목록, GET 403 정리. Phase 3 엔진 클라이언트를 만들기 전에 이 저장소에서 계약 PR로 처리하겠다(권장).
4. **확인서의 고객 표시.** 봉인 JCS 본문에 고객 이름을 원문으로 넣을지(서식 요구), 아니면 `customer_ref`만 넣고 렌더 시점에 복호화할지 정해야 한다. 원문을 넣으면 불변 본문과 Object Lock 보관물에 PII가 영구히 남고, 보존기간 파기(Phase 5)와 충돌한다. 권장은 봉인 본문에 `customer_ref`와 이름 해시만 넣고, PDF에는 서식 범위만 렌더하는 것이다.
5. **첫 DEK 경합(§7-4).** 등록 API에서 재시도로 흡수할지, 테넌트 생성 시 DEK를 미리 만들지 정해야 한다. 권장은 테넌트 생성 시 사전 생성이다.
6. **`R-TEMP-PRODUCT`와 엔진의 TEMP_PRODUCT.** 엔진은 상품이 임시등록인지 알 수 없어 TEMP_PRODUCT를 산출하지 않는다(E3 보고). 임시등록 판정은 이 시스템의 `disclosure_item.temp_product`만으로 하고, 엔진 요청에서는 그 항목을 빼거나 UNAVAILABLE로 기대하면 되는지 확인이 필요하다.
