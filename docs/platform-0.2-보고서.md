# 플랫폼 0.2.0 정비 보고서 (2026-10-14)

> 지시문 `docs/platform-0.2-정비-지시문.md`(이 PR 첫 커밋 `680fe31`). 브랜치 `work/platform-0.2`(main `60d5259`에서 분기), PR #15. 정비이며 새 기능·새 엔드포인트·
> 의존성 버전 변경은 없다. 바뀐 공개 API는 `TenantSession` 하나(추가)와 0.1.0 발행본 대비 차이(`OrgPath` 추가, OIDC 골격 삭제 — 이미 main에 있던 것)다.

## 1. 항목별 커밋·테스트

| # | 항목 | 커밋 | 증명(클래스#메서드) |
|---|---|---|---|
| — | 지시문 보관 | `680fe31` | — |
| H1 | 플랫폼 세 모듈 0.1.0 → **0.2.0**. 공개 API 덤프 `platform/API-0.2.0.txt`(발행 jar 넷 — core·core test-fixtures·canonical·spring — 의 public 클래스, public·protected 멤버, 상수 값, 자원 이름·SHA-256; `javap -protected -constants`). 루트 `verifyPlatformApi`가 `check`와 발행 워크플로에서 지금 코드의 덤프를 그 버전의 파일과 대조한다 — 다르면 "버전을 올리고 새 덤프" 안내와 함께 실패, 파일이 없어도 실패. 새 덤프는 `./gradlew platformApiDump`. 발행 워크플로는 올린 파일(jar·sources·test-fixtures·POM·`.module`)의 `sha256sum`과, `~/.m2`를 비운 뒤 GitHub Packages에서 다시 받은 jar의 `PLATFORM_SHA256`을 로그에 남긴다(소비 검증 빌드 `verification/published-consumer`는 버전을 루트에서 받는다 — 하드코딩 0.1.0 제거). 0.1.0 결함은 설계서 §3.3(v1.19)·`DECISIONS.md`에 | `7c888a8` | `verifyPlatformApi`(주입 PL-8·9·10) |
| H2 | `contracts/events/v1/envelope-core.schema.json` — 포털 §4.1의 일곱 필드(`eventId`·`seq`·`type`·`version`·`occurredAt`·`aggregate{kind,id}`·`payload`), `type`·`kind`는 문자열 정규식, 다른 파일 참조 없음. `envelope.schema.json`은 `allOf` 첫 항으로 핵심을 포함하고 나머지는 그대로(추가형). CHECKSUMS 갱신 | `7c888a8` | `ContractSchemaTest#envelopeCoreIsTheSharedFieldSetAndStandsAlone`(필드 집합·바깥 참조 0·형식 정규식 = `defs`), `#disclosureEnvelopeDerivesFromCore`, `#everySamplePassesTheCore`(11), `#aDocumentTheCoreRejectsIsRejectedByTheDisclosureEnvelope`(19 위반 — 핵심도 파생도 거부), `#anEngineEventPassesTheCoreButNotTheDisclosureEnvelope`. 추가형의 증명: 기존 샘플 시험 전부 + 아웃박스가 적재 전에 모든 이벤트를 이 envelope로 검증하므로(`EventContract`) 이벤트를 만드는 통합 테스트 전부(`OutboxEventsIT` 포함) |
| H3 | 계약 전 파일의 `format` 0 — **6개** 제거(이벤트 `defs` 2, 룰 번들 2, 룰 버전 1, 증거 매니페스트 1; 모두 `pattern`이 함께 있어 검증 동작은 같다). 시험 범위를 `contracts/` 아래 JSON·YAML 전부로 | `7c888a8` | `ContractSchemaTest#noContractFileHasAFormatKeyword`(주입 PL-4) |
| H6 | `com.ga.platform.spring.jdbc.TenantSession.SETTING_NAME = "app.tenant_id"`(public static final). `TenantSessionBinder`의 SQL이 이 상수로 만들어진다. ga-disclosure의 대조 시험은 **모든** 마이그레이션이 읽거나 쓰는 설정 이름이 이 상수이거나 닫힌 표식 목록(`ga.destroy`·`ga.rewrap`, 폐기 항목이 남으면 실패)뿐임을 본다 | `7c888a8` | `TenantSessionBinderTest#bindsTheSettingNamedByThePublicConstant`, `RlsSettingNameTest#rlsPoliciesReadThePlatformSettingName`, `#everySessionSettingInTheMigrationsIsTheTenantSettingOrAKnownMarker` |
| — | 설계서 v1.19(§3.3·§4.5·§9·§11), `DECISIONS.md`(4항목), `DEVELOPMENT.md`(소비 규약 — mavenLocal 우선 문언 삭제), `event-feed.md` | `7c888a8` | — |

## 2. 주입 기록 (주입 → 실패 확인 → 제거)

| # | 주입 | 결과 |
|---|---|---|
| PL-1 | `TenantSession.SETTING_NAME`을 `app.tenant`로 | `TenantSessionBinderTest` 2건, `RlsSettingNameTest` 2/2 실패 |
| PL-2 | `V2__rls.sql`의 정책 하나를 `app.tenantid`로(작업 트리에서만, 커밋하지 않음) | `RlsSettingNameTest` 2/2 실패 — `{V2__rls.sql=[app.tenantid]}` |
| PL-3 | 표식 목록에 쓰이지 않는 `ga.retired` | `RlsSettingNameTest` 1건 — stale marker entries |
| PL-4 | `defs`의 `date`에 `"format": "date"` 되돌림 | `noContractFileHasAFormatKeyword` — `{events/v1/defs.schema.json=1}` |
| PL-5 | disclosure envelope의 `allOf` 첫 항(핵심 참조) 제거 | `disclosureEnvelopeDerivesFromCore` |
| PL-6 | 핵심 `seq`의 `minimum: 1` 제거 | `aDocumentTheCoreRejects…[seq-zero]` — core rejects seq-zero |
| PL-7 | 핵심의 `eventId`를 `defs.schema.json#/$defs/uuid` 참조로 | `envelopeCoreIsTheSharedFieldSetAndStandsAlone` — 바깥 참조 |
| PL-8 | 버전 0.2.0 그대로 `Won.isPositive()` 공개 메서드 추가 | `verifyPlatformApi` — "플랫폼 0.2.0의 공개 API가 platform/API-0.2.0.txt와 다르다" |
| PL-9 | `platform/API-0.2.0.txt` 치움 | `verifyPlatformApi` — "API-0.2.0.txt가 없다" |
| PL-10 | 자동 구성 등록 파일(`META-INF/spring/…AutoConfiguration.imports`)에 줄 추가 — 클래스 API는 그대로 | `verifyPlatformApi` — 자원 해시 차이로 실패 |

주입 10건(ID 단위). **절차 실수 하나**: 첫 주입 스크립트가 PL-1을 되돌리지 못했고(새 파일이라 `git checkout`이 듣지 않았다) PL-2는 BSD `sed`가 적용하지 않았다 — 그 실행의 PL-2·PL-3 결과는 PL-1의 실패가 섞인 것이라 버렸다. 상수를 복원하고 깨끗한 상태(9/0·2/0)를 확인한 뒤 PL-2·PL-3을 하나씩 다시 주입해 위 결과를 얻었다.

## 3. 테스트 수

- 로컬 강제 전체 실행 `TZ=UTC ./gradlew build --rerun-tasks`(Docker 있음): **JVM 13,871, 실패 0·스킵 0**, 6분 39초. Phase 8 최종(13,835) + 36 —
  `platform-spring` +1, `disclosure-infra` 단위 +2, `disclosure-rules` +33(`ContractSchemaTest` 302 → 335).

  | 모듈 | 태스크 | 수 |
  |---|---|---|
  | platform-core | test | 3,067 |
  | platform-canonical | test | 1,054 |
  | platform-spring | test | 13 |
  | disclosure-domain | test | 1,094 |
  | disclosure-rules | test | 2,506 |
  | disclosure-workflow | test | 1,372 |
  | disclosure-seal | test | 70 |
  | disclosure-sign | test | 515 |
  | disclosure-audit | test | 142 |
  | disclosure-infra | test · integrationTest | 22 · 3,781 |
  | disclosure-app | archTest · integrationTest | 81 · 154 |

- CI: PR #15 run [`38544263021`](https://github.com/hjryoo-ai/ga-disclosure/actions/runs/38544263021)(head `7c888a8`, `pull_request`) — 일곱 잡 전부 success. 아티팩트에서 센 수: `build` JVM **13,871**(모듈별로 위 표와 같다),
  실패 0, `verifyPlatformApi` 통과("platform 0.2.0 public API matches"); `deploy-lint` 47·`images` 5(실패 0); `e2e` Playwright 12(실패 0); `kind` 회전 PASS 32·FAIL 0,
  복구 `RESTORE_TABLES MATCH tables=38`, `LOGSCAN … hits=0`; `no-docker` 통합 테스트 실패로 성공; `pdfa-verify` success.

## 4. 지시문과 다르게 한 것

| # | 지시문 | 한 것 | 이유 |
|---|---|---|---|
| D-1 | 핵심 필드 `eventId·seq·type:string·schemaVersion·tenantId·aggregate{kind:string,id}·occurredAt·payload:object` | **`schemaVersion`·`tenantId` 대신 `version`, 테넌트 없음** — §4.1의 일곱 필드 | Envelope의 필드는 Phase 1에서 포털 §4.1 원문에 맞춘 일곱 개다(`envelopeFollowsPortalConvention`). `schemaVersion`은 피드 응답의 필드이고 테넌트는 발행자 인스턴스·피드 인증이 정한다. 목록대로면 기존 이벤트가 전부 핵심에서 실패해 같은 표의 "추가형(기존 이벤트 전부 통과)"과 모순이다 — 지시문 오류로 보고 §4.1 쪽을 택했다(DECISIONS [지시문 오류]) |
| D-2 | 포털 v1.2: "`envelope-core`(+ 그것이 참조하는 defs)" | 핵심은 **아무것도 참조하지 않는다**(`uuid`·`instant` 정규식을 자기 `$defs`에) | 포털이 파일 하나만 가져가면 된다. 같은 정규식이 두 곳에 있으므로 시험이 `defs`와 대조한다(PL-7) |
| D-3 | 파생 = `allOf: [core, {type enum, aggregate.kind enum, payload oneOf 11}]` | `allOf` 첫 항에 핵심을 더하고, 기존 열거·`type`/`version`별 `if/then` payload 선택은 그대로 | `oneOf`로 바꾸면 payload 스키마끼리 겹칠 때(예: `PolicyLinked` v1·v2) 결과가 달라질 수 있다 — 추가형을 지키는 가장 작은 변경. 의미는 같다(핵심 ∧ 기존 제약) |
| D-4 | `defs`의 `format` 2곳 | 6곳 | 범위를 `contracts/` 전체로 넓힌 시험이 룰·봉인 스키마의 4곳을 더 찾았다 |
| D-5 | API 덤프 = "클래스·공개 멤버의 정렬된 목록" | + protected 멤버(`TenantScopedRepository`의 하위 클래스 API), 상수 값(`SETTING_NAME`의 값이 바뀌면 실패), jar 자원의 이름·해시(자동 구성 등록 — PL-10) | 소비자가 받는 것 중 버전이 지켜야 하는 것. 남는 틈: API가 같은 구현 변경은 덤프가 보지 못한다 — 소비자의 jar SHA-256 고정(포털 C9)이 잡는다 |
| D-6 | 상수 ↔ `V2__rls.sql` 대조 | V2 대조 + **모든** 마이그레이션의 설정 이름이 상수이거나 닫힌 표식 | 철자가 틀린 설정은 정책이 늘 NULL을 읽어 오류 없이 0행이 된다 — V2 밖에도 마이그레이션 11개(V5~V21)의 함수·정책 30곳에도 같은 이름이 있다 |

## 5. 0.2.0 jar SHA-256

발행은 병합 뒤 태그 `platform-v0.2.0`에서 한다(지시문). 그래서 **발행본의 값은 이 보고서에 아직 없다** — 발행 워크플로 로그(올린 파일의 `sha256sum`, 다시 받은
jar의 `PLATFORM_SHA256`)에서 옮겨 핸드오프 PR #14 §6에 적는다(#14는 이 PR 뒤에 병합). 지금 있는 것은 같은 커밋을 빌드한 값이다:

| 파일 | 로컬(macOS, Temurin 25.0.4.1) | CI build 잡(Ubuntu, run `38544263021`의 `PLATFORM_SHA256`) |
|---|---|---|
| `platform-core-0.2.0.jar` | `981ad2635c56a39299beb40ce0883bf7e0d7d1f6c66e7fea833668378f76f35c` | `981ad2635c56a39299beb40ce0883bf7e0d7d1f6c66e7fea833668378f76f35c` |
| `platform-core-0.2.0-test-fixtures.jar` | `c4f4e680b61bc9fd97c93c398d00c838c82199ebe0d64a4847b59e2539966355` | `c4f4e680b61bc9fd97c93c398d00c838c82199ebe0d64a4847b59e2539966355` |
| `platform-canonical-0.2.0.jar` | `14856a61d9e311d6b9f229a232a73bde58ee425d2ff5796b244dc404a7632ca0` | `14856a61d9e311d6b9f229a232a73bde58ee425d2ff5796b244dc404a7632ca0` |
| `platform-spring-0.2.0.jar` | `7b45f07bb30126491861cb537335c61b5476effb767a8ef01403e98c032524e0` | `7b45f07bb30126491861cb537335c61b5476effb767a8ef01403e98c032524e0` |

두 기계의 빌드가 바이트 단위로 같다(Gradle 9의 재현 가능 아카이브 — 고정 시각·정렬된 항목). 발행 잡도 같은 커밋·같은 JDK 메이저에서 빌드하므로 같은 값이 예상되지만, **발행본의 값은 발행 로그로만 확정한다**. 이 머신의 `~/.m2`에는 `verifyPublishedPlatform`이 쓴 0.2.0이 있다(0.1.0은 지시문대로 그대로 둔다). 발행본과 바이트가 다르면 포털의 SHA-256 고정이
그 사본을 거부한다 — 의도한 동작이다.

## 6. GitHub Packages 읽기 권한 — 사용자 조치

- 포털 저장소(`ga-agent-portal`)의 Actions가 `GITHUB_TOKEN`으로 이 저장소의 패키지를 읽으려면, 패키지마다(`com.ga.platform.platform-core`·
  `com.ga.platform.platform-canonical`·`com.ga.platform.platform-spring`) **Package settings → Manage Actions access → Add repository → `ga-agent-portal`, 역할 Read**가
  필요한 것으로 안다. 화면 이름은 GitHub 문서 기준이고 직접 확인하지 않았다(이 머신의 `gh` 토큰에는 `read:packages`가 없다).
- 확인 방법: 포털 C9의 첫 CI에서 `~/.m2`를 비운 상태의 의존성 해석이 401/403 없이 끝나고, 받은 jar의 SHA-256이 의존성 검증 메타데이터와 같다.
  실패하면 위 설정이 먼저다.

## 7. 병합 순서

1. 이 PR #15 — 심사 회신 뒤 병합(merge commit).
2. 병합 커밋에 태그 `platform-v0.2.0` → `publish-platform.yml`이 버전·API 덤프 확인 → 발행 → SHA-256 로그.
3. 핸드오프 PR #14 — §6에 발행본 SHA-256을 옮기고, 심사 회신 뒤 병합. 포털의 `UPSTREAM`은 #15의 병합 커밋(핵심이 처음 들어간 커밋)을 가리킨다.
4. 포털 Phase 0(v1.2) — 0.2.0 발행 전에는 계획만.

## 8. 질문

1. D-1: 핵심 필드를 §4.1의 일곱 개로 둔 것을 확인해 달라. 포털 v1.2 §5의 핵심 설명도 같은 목록을 전제한다.
2. 0.1.0 발행본의 API 덤프(`platform/API-0.1.0.txt`)를 기록으로 남길지 — 태그 `platform-v0.1.0`의 소스로 만들 수 있다. 지금은 0.2.0만 있다.
