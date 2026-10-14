# 아키텍처 — 규칙이 어디서 강제되는가

> 처음 보는 엔지니어를 위한 문서다. 설계 정본은 [`설계서.md`](설계서.md), 작업 규칙은 [`../CLAUDE.md`](../CLAUDE.md). 이 문서는 **설계서 §0의 대전제 7개와
> CLAUDE.md 절대 규칙 9개가 코드의 어디에서 기계적으로 강제되는지**, 그리고 **강제되지 않는 곳이 어디인지**를 적는다. 그림은 [`img/architecture.svg`](img/architecture.svg).

![아키텍처](img/architecture.svg)

## 요약

| 대전제(설계서 §0) | 절대 규칙(CLAUDE.md) | 주 강제 지점 | 강제 없는 부분 |
|---|---|---|---|
| ① 확인서는 봉인 뒤 불변, 정정은 새 버전 | 2 | DB 트리거(GD001~GD004·GD030·GD094·GD100), 파기 전용 롤·함수(GD113·GD114), 쓰기 경로 FQN 허용 목록 스캔, 상태표 ↔ 설계서 대조 | — |
| ② 룰은 데이터, 발급 시 룰 버전 박제 | 4 | 해석기(기준일 필수·Ambiguous), DB 배타 제약, 룰·서식 버전 불변 트리거, 박제 CHECK·트리거, 코드 리터럴 스캔(Java·화면) | — |
| ③ 돈의 산출은 엔진 | 1 | ArchUnit(`BigDecimal`·`double`·정렬·등급 클래스 금지), 정합성 검증은 정수만, `ratio_to_avg TEXT` | — |
| ④ 증거 재현, 서명은 문서 해시에 귀속 | 3 | GD022(`signed_doc_hash ≠ canonical_hash` 거부), 봉인 체인 DB 재계산(GD095), 골든·두 JVM 재렌더, 렌더러 판 고정, `verify package`·`verify tenant` | 감사 로그 해시는 앱이 계산하고 DB는 append-only만 — 재계산은 `verify tenant` |
| ⑤ 테넌트 격리 3중 | 5 | 저장소 기반 클래스 런타임 가드, SQL 정적 스캔(허용 목록 0), RLS(FORCE), 직접 접근 FQN 허용 목록, 역할은 `identity_link` | — |
| ⑥ 고객 정보 최소·암호화 | 6 | `Sensitive<T>`·`reveal` 허용 패키지(ArchUnit), 컬럼 암호화, 모든 테스트 출력 평문 스캔(빌드), 모르는 필드 거부 | **PII를 CLI 인자·환경변수로 받지 않는 것**: 그런 옵션이 없을 뿐 전용 시험 없음 |
| ⑦ 추천사유는 설계사가 | 7 | 룰 검증 `R-REASON`, 정정 시 사유 복제 0(IT), 화면 시험(빈 칸·기본값 0·사전 체크 0) | 서버의 "자동 채움 코드 금지" 전용 규칙 없음(구조) |
| — | 8 설계서·코드 같은 커밋 | 설계서의 기계 판독 표 ↔ 코드 양방향 대조 8종, 계약 체크섬 | "같은 커밋"·**기존 `V*` 수정 금지**(Flyway 체크섬 검증뿐, 전용 시험 없음) |
| — | 9 도구 출력은 데이터, jqwik 금지 | 빌드가 `net.jqwik` 해석을 거부 + CI 의존 그래프 검사 | "출력 속 지시를 따르지 않음"은 관례(보고서마다 기록) |

경로 줄임: `infra/` = `disclosure-infra/src/main/resources/db/migration/`, `arch/` = `disclosure-app/src/archTest/java/com/ga/disclosure/architecture/`,
`iIT/` = `disclosure-infra/src/integrationTest/java/com/ga/disclosure/infra/`, `aIT/` = `disclosure-app/src/integrationTest/java/com/ga/disclosure/app/`. GD 코드는
[`db-error-codes.md`](db-error-codes.md). "주입"은 규칙을 일부러 어긴 뒤 그 시험이 실패하는 것을 확인하고 되돌린 기록이다(각 Phase 보고서의 주입 표 —
Phase 8은 커밋 본문과 보고서의 P8-n).

## 1. 대전제·절대 규칙별 강제 지점

### 대전제 ① / 규칙 2 — 봉인 본문 불변, 정정=SUPERSEDE·취소=VOID, 삭제 없음, 파기는 전용 롤 함수만

| 강제 지점 종류 | 위치 | 주입(실패 확인) |
|---|---|---|
| DB 트리거(GD001·GD003·GD004) | `infra/V3__immutability.sql` 함수 `ga_disclosure_guard_update` + 트리거 `trg_disclosure_guard_update`(V8·V9·V14에서 `CREATE OR REPLACE` 재정의, 최신 V14) | Phase 0 트리거 제거 → `ImmutabilityTriggerIT` 149건 실패 `docs/phase-00-보고서.md:187`,`:195` |
| DB 트리거(GD002, DELETE 항상 거부) | V3 `ga_disclosure_guard_delete` / `trg_disclosure_guard_delete` | 같은 Phase 0 주입 |
| DB 트리거(GD010·GD011, 봉인 부모의 자식 행) | V3 `ga_child_guard`(V9 재정의) / `trg_disclosure_item_guard`·`trg_recommendation_guard` | 〃 |
| DB 트리거(GD030 append-only·TRUNCATE) | V3 `ga_append_only`, `trg_*_append_only`·`trg_*_no_truncate`(signature·audit_log·document_artifact·audit_anchor·disclosure·disclosure_item·recommendation) | Phase 0 → `AppendOnlyTriggerIT` 12 `docs/phase-00-보고서.md:196` |
| DB 트리거(GD094 보존기한 연장만) | `infra/V7__seal.sql` `ga_disclosure_retention_guard` / `trg_disclosure_retention_guard` | 3B J5 `docs/phase-03B-보고서.md:223` |
| DB 트리거(GD100 사유·시각 1회 쓰기) | `infra/V8__sign.sql` `ga_disclosure_guard_update` 재정의 | 4 I12·I13 `docs/phase-04-보고서.md:365-366` |
| DB 트리거·함수(파기: GD113 분기 밖 변경 거부, GD114 판정 실패) | `infra/V9__anchor_retention.sql` `ga_destroy_branch`, `ga_destroy_preconditions`, 함수 `ga_document_key_shred`·`ga_disclosure_destroy`·`ga_customer_ref_destroy`(V11·V14 재정의), 소유 롤 `disclosure_destroy_definer` | 5 V1·V2(롤·표식) `docs/phase-05-보고서.md:375-376`, V3~V8 `:377-382`, B2-1·B2-2 `:439-440`, P-1 `:458` |
| DB 함수(초안 폐기 GD133·GD137) | `infra/V14__compliance_contract_abandon.sql` `ga_draft_abandon`(V17·V18 재정의) | 6B Y1~Y9("DELETE로 폐기" 등) `docs/phase-06B-보고서.md:220` |
| 애플리케이션 — 상태표 | `disclosure-domain/.../domain/disclosure/DisclosureStateTable.java`(표 밖 = `IllegalTransition`), `DisclosureStatus.isMutable()`; 애그리게이트 `disclosure-workflow/.../workflow/disclosure/Disclosure.java` | 3A I1(설계서 블록 행 삭제) `docs/phase-03A-보고서.md:219` |
| 단위 테스트 | `DisclosureStateTableTest.designDocumentTableEqualsTheCodeTableCellByCell`, `terminalStatesAllowNothingAndMutableStatesNeverReachSealedExceptBySeal` | 〃 |
| 통합 테스트 | `iIT/ImmutabilityTriggerIT`(`bodyColumnUpdateAllowedOnlyWhileMutable`, `deleteAlwaysRejected`, `sealedCannotRegressToMutable`, `supersededByIdIsWriteOnce`, `retentionUntilOnlyExtends`, `ownerRoleIsAlsoBoundByTriggers`, `databaseMutableSetMatchesDomainEnum`), `AppendOnlyTriggerIT`, `DestroyerRoleIT`(`destructionNullsOnlyTheDesignatedColumnsAndLeavesATombstone`, `theDefinerWithoutTheMarkerIsRejectedByTheTriggers`), `TombstoneIT.onlyTheDesignatedColumnsBecomeNullAndTheAuditHashesMatchTheErasedValues`, `LifecycleIT.supersedeIsOnlyForSealedDisclosures` | 위 행들 |
| 파기 감사 = 지운 값 해시 | `TombstoneIT.onlyTheDesignatedColumnsBecomeNullAndTheAuditHashesMatchTheErasedValues`, `CustomerRefDestructionIT`, 설계서 `pii-columns` 블록 ↔ DB `PiiColumnTableTest.theBlockAndTheDatabaseAgreeBothWays` | 5 G14-5 `docs/phase-05-보고서.md:457`, S8-3·S8-4 `:443-444` |
| 아키텍처 스캔(쓰기 경로 FQN#메서드 허용 목록) | `arch/DisclosureWriteScanTest.disclosureTablesAreWrittenOnlyByTheAllowedRepositoryMethods`·`allowlistHasNoStaleEntries`(disclosure·자식·sign_session·signature·outbox — DELETE 허용은 `DisclosureRepository#save`의 자식 교체뿐), `arch/SealWriteScanTest.sealTablesAreWrittenOnlyByTheirRepositoryMethods` | 3A I4 `docs/phase-03A-보고서.md:223`, 3B I5 `docs/phase-03B-보고서.md:210`, 4 S8·L4 `docs/phase-04-보고서.md:421`,`:402` |

### 대전제 ② / 규칙 4 — 룰은 데이터, 기준일 필수·단건 해석·Ambiguous, 발급 시 룰 버전 박제

| 종류 | 위치 | 주입 |
|---|---|---|
| 단위(해석기) | `disclosure-rules/.../rules/resolve/RuleResolver.java`; `RuleResolverTest.asOfIsMandatory`, `twoGlobalRulesAreAmbiguous`, `twoTenantRulesAreAmbiguous`, `retiredAndActiveOverlapIsAmbiguousToo`, `noGlobalRuleFails`, `accessorsHaveNoDefaults`, `loadUsesThePinnedIdsEvenWhenResolutionWouldNowDiffer` | 4 P2 `docs/phase-04-보고서.md:408` |
| DB 제약(배타, `23P01`) | `infra/V4__rule_bundles.sql` `ex_rule_version_in_force_overlap`, `ex_form_template_overlap` → `iIT/RuleVersionExclusionIT.overlappingInForceRejected` | Phase 0 제약 제거 `docs/phase-00-보고서.md:187`; 1 V4 주입 `docs/phase-01-보고서.md:187` |
| DB 트리거(GD040~045, GD050~052 룰·서식 버전 불변) | V4 (`docs/db-error-codes.md`) → `RuleVersionGuardIT`, `FormTemplateGuardIT` | 1 V4 주입 `docs/phase-01-보고서.md:187` |
| 박제(룰 버전 고정) | DB CHECK — `SealColumnCheckIT.pinnedRuleVersionIsRequired`; GD081(V7 승인 = 고정 룰) `SealTriggerIT.approvalsCarryThePinnedRuleVersions`; GD104(V8 서명자 = 고정 GLOBAL 룰 `signerSet`); 로드 가드 `PinnedRuleGuardIT`; `RuleFreezeIT.boundaryDraftsPinTheirOwnVersionsAndIgnoreLaterRuleData` | 3A I7 `docs/phase-03A-보고서.md:226`, 3B J7·N4 `docs/phase-03B-보고서.md:225`,`:236`, 4 I4·P1 `docs/phase-04-보고서.md:357`,`:407` |
| 소스 스캔(코드 리터럴 금지) | `disclosure-rules/src/test/.../NoRuleLiteralsTest.mainSourcesContainNoReasonCodeOrSignerRoleLiterals`; `disclosure-seal/src/test/.../NoFieldCodeLiteralsTest.rendererSourcesNameNoTemplateCode`; `arch/LabelLiteralScanTest.noTemplateLabelIsACodeLiteral`·`hangulLiteralsLiveOnlyInTheClosedListOfFiles`; 화면 `disclosure-web/src/test/literalScan.test.ts` | 1 C9 `docs/phase-01-보고서.md:188`, 3B I4 `docs/phase-03B-보고서.md:209`, 7 0단계·W3a·b `docs/phase-07-보고서.md:131`,`:134` |
| 통합(값을 데이터로 바꾸면 행동이 바뀜) | `RuleAsDataIT.scenarioA_regulationChangeOnTheBoundaryDay`·`scenarioC_reasonCodeIsAddedByData`, `SignRulesAsDataIT.managerConfirmOffCompletesWithTwoSigners`, `DestructionRulesAsDataIT.theRetentionLengthComesFromTheRule`, `GradeConsistencyCheckTest.policiesAndTieBreakMustBeAllowedByTheRule` | 6A V5(데모 번들 `minCompare`) `docs/phase-06A-보고서.md:254` |
| 계약 스키마 | `contracts/rules/v1/rule-bundle.schema.json`·`rule-version.schema.json`·`form-template.schema.json` → `ContractSchemaTest`; 발행 번들 동결 `contracts/rules/released-bundles.txt` → `ReleasedBundlesAreFrozenTest` | 5 R1·R2·R5 `docs/phase-05-보고서.md:390` |

### 대전제 ③ / 규칙 1 — 수수료율 연산 금지, ratioToAvg 불투명 문자열

| 종류 | 위치 | 주입 |
|---|---|---|
| ArchUnit | `ArchitectureRulesTest.bigDecimalOnlyInInfraJson`(허용: `infra.json`, `audit.tsa.stub`), `ratioLabelValueOnlyInAllowlistedPackages`(`RatioLabel.value()` 5개 패키지), `gradeSnapshotIsNeverOrderedOutsideConsistencyCheck`(Comparable 금지 + 정렬은 `GradeConsistencyCheck`만), `noGradingRankingOrCommissionRateClasses`, `noDoubleOrFloat` | Phase 0 `TmpRatioSorter`·`TmpCommissionRateCalculator`·`TmpFloatingField`·`RatioLabel implements Comparable` `docs/phase-00-보고서.md:165-171`; 1 허용 목록 주입 `docs/phase-01-보고서.md:185`; 5 A2 `docs/phase-05-보고서.md:408` |
| 정합성 검증(정수만) | `disclosure-rules/.../rules/grade/GradeConsistencyCheck.java`(rankInSet·gradeOrdinal) → `GradeConsistencyCheckTest`(`strictRequiresAPermutation`, `gradeOrdinalMustNotDecreaseAlongRankAndTiesShareIt` 등) | — |
| DB 컬럼 | `infra/V1__init.sql` `disclosure_item.ratio_to_avg TEXT`(CHECK 없음), V6 생성 컬럼 `ratio_present` → `RatioLabelRoundTripIT.columnIsTextWithoutFormatCheck`, `ratioToAvgRoundTripsByteForByte` | 3A 개발 중 실제 위반 검출 `docs/phase-03A-보고서.md:232` |
| 계약 스키마 | `contracts/api/v1/engine-disclosure.openapi.yaml`(ratioToAvg = 불투명 문자열), 수신 즉시 검증 `disclosure-infra/.../infra/engine/EngineGradeClient.java` → `GradeSnapshotIT.invalidEngineResponsesNeverBecomeSnapshots`; `contracts/seal/v1/canonical.schema.json` ratioToAvg `type: string` | 3A I3 `docs/phase-03A-보고서.md:222` |
| 빌드(Spring·DB 무의존 모듈) | 루트 `build.gradle.kts` `verifySpringFree`(platform-core·platform-canonical·domain·rules·seal·sign), `verifyNoRuntimeDependencies`(platform-core) + ArchUnit `springAndDbFreeModules` | Phase 0 `TmpSealJdbc` `docs/phase-00-보고서.md:168`; 1 `docs/phase-01-보고서.md:186` |

### 대전제 ④ / 규칙 3 — 증거 재현성, 서명은 문서 해시에 귀속, 감사 해시체인

| 종류 | 위치 | 주입 |
|---|---|---|
| DB 트리거(GD020·GD021·**GD022** `signed_doc_hash ≠ canonical_hash`) | V3 `ga_signature_guard_insert` / `trg_signature_guard_insert`(V8 재정의) | Phase 0 트리거 제거 `docs/phase-00-보고서.md:187-196` |
| DB 트리거(GD102 PDF 해시, GD103 세션 고정 해시, GD104 서명자 집합) | `infra/V8__sign.sql` `ga_signature_guard_insert`; 세션 발급 시 두 해시 고정 GD101 `ga_sign_session_guard` | 4 I1·I3·I4·I6 `docs/phase-04-보고서.md:354`,`:356-357`,`:359` |
| 애플리케이션 | `disclosure-workflow/.../workflow/disclosure/SignService.java` `customerChecks`(`HASH_CHANGED`), `Completion.java`, `disclosure-seal/.../seal/evidence/EvidencePackageBuilder.java`, 검증 `disclosure-audit/.../audit/verify/PackageVerifier.java` | 4 K6 `docs/phase-04-보고서.md:393` |
| 통합·단위 | `AppendOnlyTriggerIT.signatureWithDifferentDocumentHashRejected`·`signatureHashOfAnotherVersionRejected`, `SignatureBindingIT.signatureBindsBothHashesOnlyOnSignableStatuses`·`sessionPinnedToOtherHashesRejected`, `VerifyPackageTest.aSignatureBoundToAnotherDocumentIsReported` | 위 |
| 정규화·해시 | `platform-canonical/.../canonical/Canonicalizer.java`(RFC 8785) → `CanonicalizerTest`; 봉인 체인 DB 재계산 GD095 V7 `ga_disclosure_seal_integrity`/`trg_disclosure_seal_integrity`(`chain_hash = SHA-256(prev‖canonical‖pdf)`), 카운터 GD090 `ga_disclosure_counter_guard`, 머리 GD091 `ga_disclosure_chain_head_guard` → `SealColumnCheckIT.chainMustExtendTheHead`, `SealTriggerIT`, `SealChainIT` | 3B J1·J2·J6·J9 `docs/phase-03B-보고서.md:219-220`,`:224`,`:227` |
| 결정론(같은 입력 = 같은 바이트) | ArchUnit `sealBodyAndRendererAreEnvironmentFree`(SealRules), `sealPathDoesNotUseTheScalarWrappingConvention`, `productionSealsOnlyWithTheCurrentRendererVersion`; `SealGoldenTest.canonicalAndPdfMatchTheCommittedExpectations`, `RenderDeterminismIT.storedDocumentRerendersByteForByteInTwoDifferentJvms`, `SignedPdfGoldenTest` | 3B I1·I2 `docs/phase-03B-보고서.md:206-207`; 4 K1·K2 `docs/phase-04-보고서.md:388-389`; 8 P8-16a·b·P8-17(`a85365f`) |
| 감사 체인 | 계산 `disclosure-audit/.../audit/AuditChain.java`(앱), DB는 append-only(GD030 `trg_audit_log_append_only`)만 — **감사 entry_hash를 DB가 재계산하지는 않는다**(앵커 GD110이 `audit_head`만 대조); `AuditChainTest`, `AuditAppendIT.tamperingThatBypassesTheTriggerIsDetectedByRecomputation`, `VerifyTenantIT.aRewrittenAuditEntryHashBreaksTheAuditChainAndFlagsThatRow`, `ChainWalkersTest` | 5 V1′ `docs/phase-05-보고서.md:425`, C1·C2 `:405-406` |
| 앵커·영수증(GD110·GD111) | V9 `ga_anchor_guard_insert`, `ga_anchor_receipt_guard_insert` → `AnchorGuardIT` | 5 V9·V10·V11 `docs/phase-05-보고서.md:383-385`, M1·M3 `:396`,`:398` |
| 계약 스키마 | `contracts/seal/v1/canonical.schema.json`, `evidence-manifest.schema.json`, `contracts/verify/v1/verify-report.schema.json` | — |

### 대전제 ⑤ / 규칙 5 — tenant_id 없는 접근 금지(3중 격리), 역할은 identity_link

| 종류 | 위치 | 주입 |
|---|---|---|
| ArchUnit(저장소 상속·원시 JDBC 허용 목록) | `ArchitectureRulesTest.dbAccessOnlyViaTenantScopedRepository`(규칙 본체 `platform-core/.../core/arch/ArchRules.java` `dbAccessOnlyVia`: `java.sql..`·`javax.sql..`·`org.springframework.jdbc..` 참조는 `TenantScopedRepository` 하위·허용 목록만, 중첩 클래스 노출 금지), `allowlistsHaveNoStaleEntries`, `flywayOnlyInTheMigrator` | Phase 0 `TmpRogueDao` `docs/phase-00-보고서.md:169`; 1 `TenantSessionBinder` 중첩 클래스 `docs/phase-01-보고서.md:185`; 8 P8-8·P8-9(`e1616ff`) |
| 런타임 가드 | `platform-spring/.../jdbc/TenantScopedRepository.java`(`:tenantId` 없으면 `MissingTenantPredicateException`), `TenantSessionBinder`(트랜잭션마다 `set_config('app.tenant_id')`) → `TenantContextGuardTest.sqlWithoutTenantPlaceholderIsRejectedBeforeDatabase`·`callerCannotSupplyTenantParameter`, `TenantSessionBinderTest.rebindingToAnotherTenantInsideTransactionIsRejected`, `aIT/api/TenantBindingOrderIT.dataAccessBeforeBindingFailsClosed` | 6A K1 `docs/phase-06A-보고서.md:267` |
| SQL 정적 스캔 | `arch/TenantPredicateScanTest.everyTenantTableAccessHasTenantPredicate`(스캐너 `SqlTenantScanner`, 허용 목록 `ALLOWLIST = Map.of()` — 비어 있음) | Phase 0 V99 주입 `docs/phase-00-보고서.md:179-181`; 6A K1·K1b(K1b는 스캔만 실패, RLS가 막음) `docs/phase-06A-보고서.md:267` |
| RLS | `infra/V2__rls.sql` 정책 `tenant_isolation`(ENABLE + FORCE, `tenant_id = current_setting('app.tenant_id', true)`), V12·V14·… 새 표 같은 패턴 → `iIT/RlsIsolationIT`(`everyTenantTableHasForcedRlsAndTenantPolicy`, `policiesAreExactlyTenantIsolationPlusTheDirectoryException`, `appRoleCannotBypassOrDisableProtections`, `appRoleAttributesDenyBypass`) | Phase 0(RLS DISABLE) `docs/phase-00-보고서.md:198`; 2 I9 `docs/phase-02-보고서.md:216`; 5 V13 `docs/phase-05-보고서.md:387`; 4 I15 `docs/phase-04-보고서.md:368` |
| 역할·조직은 identity_link | `ApiLayerRulesTest.onlyTheTenantBindingFilterTouchesTheJwt`; `AuthorizationCoverageTest`(`everyEntryAuthorizesItsDeclaredActions` 등); `aIT/api/AuthzFromIdentityLinkIT.roleScopeOrgAndAgentClaimsAreIgnored`·`anIdentityLinkChangeTakesEffectOnTheNextRequest`; `AuthzMatrixTest`(설계서 인가 표 ↔ 코드) | 6A Z1~Z10 `docs/phase-06A-보고서.md:255`, P1·P2 `:269` |
| 전용 롤 단언(마이그레이션) | V9·V12·V14·V22 `DO` 블록(`pg_roles`·`pg_auth_members`) → `V22RolesIT`, `DestroyerRoleIT.theDestroyerHoldsOnlyTheThreeFunctionsAndTheAppMayOnlySwitchToIt` | 5 V3·V4 `docs/phase-05-보고서.md:377-378`; 8 P8-5·P8-6·P8-44(`e1616ff`·`af04b22`) |

### 대전제 ⑥ / 규칙 6 — 고객 PII 최소·암호화·평문 금지

| 종류 | 위치 | 주입 |
|---|---|---|
| ArchUnit | `ArchitectureRulesTest.sensitiveValuesDoNotLeakThroughRecordsFieldsOrCrypto`(`PiiRules.noRecordHoldsSensitive`, `piiValuesAreHeldOnlyInsideSensitive`, `Sensitive.reveal` 4개 패키지, `javax.crypto`는 `infra.crypto`만), `birthDateMatchUsesConstantTimeComparison`; `ApiLayerRulesTest.recordsWithSensitiveComponentsRedactThemInToString`, `rawRequestUriQueryAndHeadersAreReadOnlyInApiSecurity` | 2 I1~I5 `docs/phase-02-보고서.md:208-212` |
| 컬럼 암호화 | `disclosure-infra/.../infra/crypto/CustomerFieldCipher.java`; DB CHECK `23514`(V5 `customer_ref` 암호문 머리) → `CustomerEncryptionIT.roundTripKeepsValuesAndStoresOnlyCiphertext`·`ciphertextMovedToAnotherRowFailsToDecrypt`·`phoneIsReleasedOnlyForNotificationAndTheReadIsAudited` | 2 I8 `docs/phase-02-보고서.md:215` |
| 빌드 검사(평문 센티널) | 루트 `build.gradle.kts` `scanPlaintextLeaks`(모든 테스트 결과 XML, `check`가 의존) | 2 I7 `docs/phase-02-보고서.md:214`; 3A I9 `docs/phase-03A-보고서.md:228` |
| 통합(누출 스캔) | `PlaintextLeakScanIT`(`noPlaintextInLogsExceptionsToStringAuditOrDatabase` 등), `aIT/api/ApiPlaintextLeakScanIT.customerRegistrationLeaksTheRequestsOwnValuesNowhere`, `PublicPlaintextLeakScanIT`; E2E 센티널 스캔(Phase 7) | 2 I6 `:213`; 4 S2 `docs/phase-04-보고서.md:415`; 6A H1~H4 `docs/phase-06A-보고서.md:266`; 6B C1a~ `docs/phase-06B-보고서.md:228`; 7 W14 `docs/phase-07-보고서.md:151` |
| 수신 항목 최소 | `PiiField`(3항목), `CustomerRegisterRequest`(주민·주소·계좌 필드 없음), 앱 전체 `spring.jackson…fail-on-unknown-properties: true`(`disclosure-app/src/main/resources/application.yaml:7`) → `CustomerRegisterIT.malformedInputIsA400WithTheFieldNameOnly`(`rrn` 사례)·`unknownFieldsAreRejectedOnEveryRoute`, `CustomerFileParserTest.invalidValuesAreReportedByPositionWithoutTheValue`(`address`), `ApiRouteSetIT.theOnlyCustomerRouteIsRegistrationAndNoGetSearchesByPersonalData` | 6B V2(모르는 필드 허용) `docs/phase-06B-보고서.md:228` |
| PII를 CLI 인자·환경변수로 받지 않음 | **강제 없음 — 관례·구조**(CLI에 그런 옵션이 없고 파일 입력만; `OperatorCli` 주석·`CustomerFileParser`). 이를 단언하는 전용 테스트는 찾지 못함 | — |

### 대전제 ⑦ / 규칙 7 — 추천사유를 시스템이 채우지 않는다

| 종류 | 위치 | 주입 |
|---|---|---|
| 도메인 구조 | `Recommendation`(코드 ≥1, 텍스트는 입력만), `AgentReason`, `DisclosureCommand` — 사유를 만드는 생성 경로 없음(주석 근거) | — |
| 룰 검증(데이터) | `disclosure-rules/.../validation/standard/Reason.java`(`R-REASON`) → `ValidationRegistryTest`, `RuleAsDataIT.scenarioC_reasonCodeIsAddedByData` | — |
| 통합 | `LifecycleIT.supersedeCreatesTheNextVersionPinnedByReResolutionOfTheOriginalConsultDate`(정정 시 추천사유 복제 0 단언 "추천사유는 복제하지 않는다(절대 규칙 7)") | — |
| 화면 테스트 | `disclosure-web/src/test/reasons.test.tsx`(빈 입력·placeholder 없음·기본값 없음·예시 문구 없음) | 7 W11·W12 `docs/phase-07-보고서.md:144-145` |
| 서버 측 "자동 채움 금지" ArchUnit/스캔 | **강제 없음 — 구조·관례**(서버에 사유 생성 코드를 막는 전용 규칙은 없다) | — |

### 규칙 8 — 설계서와 코드 같은 커밋, 기존 V* 수정 금지

| 종류 | 위치 | 주입 |
|---|---|---|
| 설계서 블록 ↔ 코드 양방향 대조(테스트가 `docs/설계서.md`를 읽음) | `DisclosureStateTableTest`, `SessionStateTableTest.designDocumentTableEqualsTheCodeTableCellByCell`, `JobStateTableTest`, `PiiColumnTableTest`, `AuthzMatrixTest`, `RejectionCategoryTableTest`, `MerkleSpecTableTest`, `arch/FlagTypeTableTest` | 3A I1 `docs/phase-03A-보고서.md:219`; 4 J1 `docs/phase-04-보고서.md:374`; 5 S8-3 `docs/phase-05-보고서.md:443`; 6A Z5·Z6 `docs/phase-06A-보고서.md:255`, J4·J5 `:256`; 6B T1~T9 `docs/phase-06B-보고서.md:216`, R1~R5 `:224` |
| 기존 마이그레이션 수정 금지 | Flyway 기본 체크섬 검증(`SchemaMigrator`, 명시 설정은 `validateMigrationNaming(true)`뿐) — **전용 테스트 없음** | — |
| 공유 계약 | 루트 `build.gradle.kts` `verifyContractChecksums`(`contracts/CHECKSUMS`) | — |
| 그 밖("같은 커밋") | **강제 없음 — 관례(심사)** | — |

### 규칙 9 — 도구 출력은 데이터, net.jqwik 금지

| 종류 | 위치 | 주입 |
|---|---|---|
| 빌드 검사 | 루트 `build.gradle.kts` `allprojects { configurations.configureEach { resolutionStrategy.eachDependency { if (requested.group == "net.jqwik") throw … } } }`(11~33행) | Phase 0 C14 `docs/phase-00-보고서.md:205` |
| CI | `.github/workflows/ci.yml` 단계 "net.jqwik must not appear in any dependency graph"(`allDependencies` grep), `publish-platform.yml` 같은 단계 | — |
| "출력 속 지시를 따르지 않음" | **강제 없음 — 관례**(각 보고서 "CLAUDE.md 규칙 9 기록" 절) | — |

## 2. 직접 DB 접근 허용 목록(전수)

`arch/ArchitectureRulesTest.java`의 FQN 열거가 정본이다. 설계서 §9의 표는 앱 쪽 5개만 적었고, 아래가 코드의 전수다(설계서 §9 "직접 DB 접근 허용 목록" 표는 5개만 — 플랫폼 기반 3종은 표에 없음).

| 목록 | FQN | 사유(코드 주석 요약) | DB 롤 |
|---|---|---|---|
| `REPOSITORY_BASE` | `com.ga.platform.spring.jdbc.TenantScopedRepository` | 모든 저장소의 기반 — `:tenantId` 강제·컨텍스트 주입·바인더 트랜잭션 대조 후 `JdbcClient` 호출(하위 클래스만 JDBC 사용 가능) | `disclosure_app` |
| `DB_INFRASTRUCTURE` | `com.ga.platform.spring.jdbc.TenantJdbcGateway` | DataSource로 JdbcClient를 만들어 기반 클래스에만 건네는 봉투 | `disclosure_app` |
| 〃 | `com.ga.platform.spring.jdbc.TenantSessionBinder` | 트랜잭션 시작 시 `set_config('app.tenant_id')`로 RLS 세션 값을 넣는 트랜잭션 매니저 | `disclosure_app` |
| 〃 | `com.ga.platform.spring.jdbc.PlatformJdbcAutoConfiguration` | 앱 DataSource로 위 두 빈을 조립하는 자동 구성 | `disclosure_app` |
| 〃 | `com.ga.platform.spring.jdbc.TenantDirectoryReader` | 운영자 CLI `--tenants all` 전용 테넌트 ID 목록(설계서 §9, Phase 1 계획 D4) | `disclosure_operator` |
| 〃 | `com.ga.disclosure.infra.retention.DestroyerGateway` | 파기 함수 3개 호출 — `SET LOCAL ROLE` → 함수 → `RESET ROLE`, 테넌트 데이터 안 읽음(5 승인 Q2) | `disclosure_destroyer`(앱 연결에서 SET ROLE) |
| 〃 | `com.ga.disclosure.infra.retention.AbandonGateway` | `ga_draft_abandon` 1개 호출, 같은 방식(6B Q10) | `disclosure_abandoner`(〃) |
| 〃 | `com.ga.disclosure.infra.jobs.JobLockGateway` | 풀 없는 연결로 세션 advisory lock·`pg_locks`만(6A Q8·B1) | `disclosure_job_lock` |
| 〃 | `com.ga.disclosure.app.health.DatabaseHealthIndicator` | 헬스·스키마 버전 가드 — `SELECT 1`과 `flyway_schema_history` 최고 버전만(8 승인 Q1) | `disclosure_health` |
| `MIGRATOR`(별도 규칙 `flywayOnlyInTheMigrator`) | `com.ga.disclosure.infra.migration.SchemaMigrator` | `org.flywaydb..` 참조 유일 클래스 — 운영자 명령 `db migrate` | `disclosure_migrator` |

- 같은 클래스의 다른 FQN 허용 목록(참고): `BIG_NUMBER_PACKAGES`(infra.json, audit.tsa.stub), `BOUNCY_CASTLE_PACKAGES`(audit.tsa, audit.tsa.stub), `RATIO_LABEL_VALUE_PACKAGES`(seal.renderer, api.mapper, seal.canonical, infra.persistence, infra.json), `ORDERING_CLASSES`(rules.grade.GradeConsistencyCheck), `PII_REVEAL_PACKAGES`(domain.pii, infra.crypto, rules.pii, seal.canonical), `CRYPTO_PACKAGE`(infra.crypto), `FILE_WRITERS`(app.cli.CliFiles, infra.secret.FileSecretSource, audit.tsa.stub.LocalStubTsa, app.demo.DemoOidcIssuer, seal.renderer.RerenderMain). 전부 `allowlistsHaveNoStaleEntries`가 폐기 항목을 잡는다.
- 다른 소스의 허용 목록: `TenantPredicateScanTest.ALLOWLIST`(빈 Map), `DisclosureWriteScanTest.ALLOWED`(FQN#메서드 10개), `SealWriteScanTest`(FQN#메서드).
- 역할 설정: `disclosure-app/src/main/resources/application.yaml` 8~34행(app·operator·migrator·health·job_lock 사용자명), `application-prod.yaml`(같은 키, 기본값 없음).

## 3. 모듈 의존 방향

Gradle 주 의존(`api`/`implementation(project(...))`, 테스트·testFixtures 제외):

| 모듈 | → 의존 |
|---|---|
| platform-core | (없음, 런타임 의존 0 — `verifyNoRuntimeDependencies`) |
| platform-canonical | (프로젝트 의존 없음) |
| platform-spring | platform-core |
| disclosure-domain | platform-core |
| disclosure-rules | domain, platform-canonical |
| disclosure-seal | domain, rules, platform-canonical |
| disclosure-sign | domain |
| disclosure-audit | domain, platform-canonical |
| disclosure-workflow | rules, seal, sign, audit, platform-canonical |
| disclosure-compliance | rules, audit, platform-canonical |
| disclosure-api | workflow, compliance, platform-spring |
| disclosure-infra | platform-spring, domain, rules, audit, compliance, workflow (포트-어댑터) |
| disclosure-demo | domain |
| disclosure-app | api, infra, rules, audit, compliance, workflow, platform-spring; runtimeOnly disclosure-web |
| disclosure-web | (Node 빌드, Java 프로젝트 의존 없음; e2e가 app bootJar 사용) |

ArchUnit 강제: `ArchitectureRulesTest.layeredModuleDependencies`(`ArchRules.layeredDependencies`) — 주석 요약 "app → api → workflow/compliance → rules/seal/sign/audit → domain → platform-core; platform-spring은 infra·api·app만, platform-canonical은 rules·seal·audit·compliance(와 상위)만, infra는 app만 접근". 레이어별 허용 접근자: Rules←Seal(3B), Seal/Sign/Audit←Workflow·Compliance·Api·Infra·App·Demo, Workflow/Compliance←Api·Infra·App, Api/Infra/Demo←App. Spring·DB 무의존: ArchUnit `springAndDbFreeModules`(platform-core, domain, rules, seal — `seal.renderer` 제외) + Gradle `verifySpringFree`(platform-core, platform-canonical, domain, rules, seal, sign). 주입: Phase 0 demo→infra `docs/phase-00-보고서.md:170`.

## 4. 경로 접두 셋 — 포트·인그레스·네트워크

| 접두 | 앱 포트 | 앱 쪽 강제 | 인그레스(Traefik IngressRoute) | NetworkPolicy(`deploy/base/networkpolicies.yaml`) |
|---|---|---|---|---|
| `/api/v1` | 8080(`server.port`) | 컨트롤러 패키지 `api.rest` ↔ 접두: `ApiLayerRulesTest.controllersExistOnlyInPrefixPackagesAndMatchTheirPrefix`; 채널(API) 판정은 라우팅 결과 → `ChannelSeparationIT.eachRoleReachesOnlyItsPrefixAndEverythingElseIsTheSame404` | A 직원: `deploy/components/ingress/public.yaml` IngressRoute `staff` — `Host(staff.ga.example.invalid) && PathPrefix(/api/)` → svc `ga-app:8080`, 나머지 → `ga-web:8080`, TLS `ga-tls-staff`, 컨트롤러 `traefik-public`(ns `ga-ingress`, ingressclass `ga-public`) | `ga-app-public`: app 8080 ← `ga-ingress/traefik-public`만 |
| `/public/v1` | 8080 | 패키지 `api.publicsign`; `PublicSignGate`(내부 포트 도착 거부, 쿼리·비 POST 거부, 응답 패딩) → `PortSeparationIT.publicPathsOnTheInternalPortAreThePublicRejection`, `PublicSignUniformResponseIT` | B 서명: 같은 파일 IngressRoute `sign` — `Host(sign.ga.example.invalid) && PathPrefix(/public/)` → `ga-app:8080`(미들웨어 `strip-client-cert`, `sign-rate-limit`, `sign-body-limit`), `Path(/s) || PathPrefix(/assets/)` → `ga-web:8080`, TLS `ga-tls-sign` | 〃 (web 8080 ← 공개 진입점만: `ga-web-public`) |
| `/internal/v1` | **8081** — 추가 Tomcat 커넥터 `disclosure-app/.../app/config/InternalConnectorConfiguration.java`(`ga.internal.port`, 기본 8081; `deploy/base/app.yaml` `GA_INTERNAL_PORT`) | `disclosure-api/.../api/security/PortChannelFilter.java`(포트 ↔ 접두 불일치면 미라우팅 404, `PathPatternRequestMatcher` — 라우팅과 같은 파서), `InternalPort`, mTLS 주체 대조 `ClientCertSubjectFilter`; 패키지 `api.internal` → `PortSeparationIT.eachPrefixAnswersOnlyOnItsPort`·`noSpellingOfTheInternalPrefixReachesItsHandlersOnTheAppPort`, `ClientCertGuardIT.theGuardedPathsOpenOnlyWhenTheCertificateSubjectIsTheTokenSubject` | C 내부: `deploy/components/ingress/internal.yaml` IngressRoute `internal`(ns `ga-ingress-internal`, ingressclass `ga-internal`, entryPoint `internal`) — `PathPrefix(/internal/)` → ExternalName svc `ga-app-internal:8081`; `TLSOption default` `clientAuthType: RequireAndVerifyClientCert`(CA `ga-internal-client-ca`), TLS `ga-tls-internal` | `ga-app-internal`: app 8081 ← `ga-ingress-internal/traefik-internal`만 |
| 관리(`/actuator`) | **8082** `management.server.port: ${GA_MANAGEMENT_PORT:8082}`(`application.yaml:80`); 앱 포트의 `/actuator/**`는 내부 404 | `HealthEndpointsIT.readinessAndLivenessAreUpOnTheManagementPortOnly` | 인그레스 미노출 | `ga-app-management`: 8082 ← 라벨 `ga-disclosure/scrape=true` 네임스페이스만 |

- 기본 거부: `default-deny-ingress`(같은 파일). kind 오버레이 데이터 저장소용 NetworkPolicy: `deploy/overlays/kind-demo/postgres.yaml`, `seaweedfs.yaml`, `deploy/components/restore/kustomization.yaml`.
- 배포 린트: `disclosure-app/src/deployTest/java/com/ga/disclosure/deploy/DeployRulesTest.java` — `publicRoutesNeverReachTheInternalPortAndEveryInternalConnectionNeedsAClientCertificate`, `networkPoliciesAdmitEachAppPortFromItsEntryPointOnly`, `everyRouteFirstStripsTheHeaderTheAppTrustsAndTheSignApiIsLimited`, `noControllerCanReadTheAppNamespaceSecrets` 등. kind 실측: `deploy/scripts/kind.sh`(CI `ci.yml` kind 잡).
- 주입: 6A A1·A6 `docs/phase-06A-보고서.md:258`; 6B M1·M2(원 URI 판정) `docs/phase-06B-보고서.md:226`; 8 P8-12(앱 포트 `/internal`)·SEC-1(`/%69nternal` — 수정 전 코드에서 200), P8-25·25b·25c·P8-26(공개 라우트·NetworkPolicy·헤더 지움), P8-35~P8-39(mTLS 기본 옵션·컨트롤러 권한·원 IP).

운영 절차는 [`operations/ingress.md`](operations/ingress.md).

## 5. DB 롤 9종

| 롤 | LOGIN | 용도 | 단언 마이그레이션 |
|---|---|---|---|
| `disclosure_migrator` | LOGIN | 스키마 소유자, Flyway(`db migrate`)만; `disclosure_destroy_definer`로 SET ROLE 가능(INHERIT FALSE) | (V2 주석) |
| `disclosure_app` | LOGIN, NOINHERIT | 앱 데이터소스, DML만·RLS 적용; destroyer·abandoner로 SET ROLE만(INHERIT FALSE) | V2 GRANT |
| `disclosure_operator` | LOGIN, NOINHERIT | 운영자 CLI `--tenants all` — `tenant.tenant_id`만(`tenant_directory` 정책) | V4 |
| `disclosure_destroyer` | NOLOGIN | 파기 함수 3개 EXECUTE만 | V9 |
| `disclosure_destroy_definer` | NOLOGIN | 파기(·V21 재래핑) 함수 소유자(SECURITY DEFINER), 지정 컬럼 UPDATE·판정 읽기 | V9 |
| `disclosure_abandoner` | NOLOGIN | `ga_draft_abandon` EXECUTE만 | V14 |
| `disclosure_job_lock` | LOGIN, NOINHERIT | advisory lock·`pg_locks`만, 표·스키마 권한 0, 어떤 롤의 멤버도 아님 | V12 |
| `disclosure_health` | LOGIN, NOINHERIT | 헬스·스키마 버전 가드: CONNECT·스키마 USAGE·`flyway_schema_history(version, success)` SELECT만 | V22 |
| `disclosure_backup` | LOGIN, **REPLICATION**, NOINHERIT | `pg_basebackup` 물리 백업만, CONNECT 없음·표 권한 0 | V22 |

시험: `RlsIsolationIT.operatorSeesEveryTenantIdAndNothingElse`, `DestroyerRoleIT`, `V22RolesIT`(`healthRoleReadsOnlyTheMigrationVersion`, `backupRoleCannotOpenAnOrdinarySession`, `everyTableSelectIsRefused`, `catalogShowsNoPrivilegeBeyondTheTwoColumns`), `V12GuardIT`, `V14GuardIT`. 설계서 근거: §9 "DB 롤" 단락.

## 6. 아키텍처 시험의 규모

`disclosure-app/src/archTest`에 시험 클래스 10개·메서드 59개(파라미터화 2개 — CI 실행 수는 더 많다). 순수 ArchUnit 규칙은 `ArchitectureRulesTest`(19)·
`ApiLayerRulesTest`(8)·`AuthorizationCoverageTest`(5)이고, 나머지는 같은 소스셋의 소스·SQL·문서 스캔이다(`TenantPredicateScanTest`·`DisclosureWriteScanTest`·
`SealWriteScanTest`·`LabelLiteralScanTest`·`FlagTypeTableTest`·`ProdProfileYamlTest`·`GlobalKekPathScanTest`). 허용 목록은 전부 FQN 열거이고 폐기된 항목이
남으면 `allowlistsHaveNoStaleEntries`가 실패한다. 배포 묶음의 규칙은 `deployTest`(`DeployRulesTest`·`CronJobManifestTest`·`KubeconformTest` 등), 이미지는
`imageTest`(레이어 전수 스캔).
