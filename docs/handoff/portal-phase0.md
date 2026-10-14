# 포털 Phase 0 인계 — ga-disclosure가 확정한 것

작성 2026-10-14 · 기준 ga-disclosure `main` = `60d5259`(PR #13 병합 커밋) · 대상 `ga-agent-portal` Phase 0 지시문 v1.1(2026-10-14)
개정 2026-10-14 · H1~H6 결정 반영 — 플랫폼 0.2.0 정비(ga-disclosure PR #15, `docs/platform-0.2-정비-지시문.md`)와 포털 Phase 0 지시문 v1.2

이 문서는 포털 Phase 0 계획이 그대로 인용할 수 있게 ga-disclosure 쪽 사실을 한곳에 모은 것이다. **정본이 아니다** — 각 항목에 정본 경로를 적었고,
다르면 정본이 맞다. §0이 결정이고, §1은 그 결정을 부른 사실(지시문 v1.1의 전제 가운데 이 저장소와 달랐던 것)의 기록이다.

## 0. 결정 (2026-10-14)

| # | 결정 | 어디서 |
|---|---|---|
| H1 | 플랫폼 **0.2.0**을 발행한다. 0.1.0은 쓰지 않는다(지우지도 재발행하지도 않는다). 공개 API 덤프 `platform/API-<버전>.txt`와 다르면 ga-disclosure 빌드가 실패한다(`verifyPlatformApi`). 포털은 0.2.0 jar의 SHA-256을 의존성 검증 메타데이터에 고정하고 CI에서 `mavenLocal`을 쓰지 않는다 | ga-disclosure PR #15 → 태그 `platform-v0.2.0`, 포털 v1.2 §1·C9 |
| H2 | 발행자 공통 핵심 `contracts/events/v1/envelope-core.schema.json`을 분리했다(§4.1의 일곱 필드와 형식, 다른 파일 참조 없음). disclosure envelope는 `allOf` 첫 항으로 핵심을 포함한다(추가형). 포털은 **핵심만** 사본으로 가져가고 자기 envelope(`allOf: [core, 포털 이벤트 13종]`)를 쓴다 | ga-disclosure PR #15, 포털 v1.2 §5 |
| H3 | ga-disclosure 계약 전 파일의 `format` 0(`defs` 2개 + 룰·봉인 스키마 4개 제거), 시험 범위를 `contracts/` 전체로 | ga-disclosure PR #15 |
| H4 | 포털은 사본을 `contracts/shared/ga-disclosure/`에 두고 그 디렉터리 전용 `CHECKSUMS`·`UPSTREAM`을 둔다. `verifyUpstreamContract`는 그 디렉터리만 대조한다 | 포털 v1.2 §5 |
| H5 | `CHECKSUMS`는 **파일 바이트의 평문 SHA-256**(`sha256sum -c` 형식) 그대로. JCS(`platform-canonical`)는 이벤트 payload의 정규 해시(멱등·중복 판정)에만 쓴다 | 포털 v1.2 §5 |
| H6 | 공개 상수 `com.ga.platform.spring.jdbc.TenantSession.SETTING_NAME = "app.tenant_id"`(0.2.0). 마이그레이션 문자열과 상수를 대조하는 시험을 양쪽에 둔다(ga-disclosure `RlsSettingNameTest`, 포털 C10) | ga-disclosure PR #15, 포털 v1.2 §4·C10 |

병합 순서: 플랫폼 0.2.0 정비 PR(#15) → 태그 `platform-v0.2.0`·발행 → 이 인계 PR(#14). 포털은 0.2.0이 발행되기 전에는 계획만 쓴다.

## 1. 지시문 v1.1과 사실이 달랐던 점 (기록 — 결정은 §0)

### H1. `0.1.0`이 두 벌이다 — 발행본과 `mavenLocal`이 다르다

- GitHub Packages의 `com.ga.platform:*:0.1.0`은 태그 `platform-v0.1.0`(`db55ad7`, 2026-09-30)에서 한 번 발행됐다. 그 뒤 `platform-v*` 태그는 없다(재발행 없음).
- 그 뒤 플랫폼 소스는 **버전 문자열 `0.1.0`을 그대로 둔 채** 두 번 바뀌었다.

  | 커밋 | 변경 | 공개 API 영향 |
  |---|---|---|
  | `f9a3f01`(2026-10-03, 6A) | `platform-core`에 `com.ga.platform.core.tenant.OrgPath` 추가 | 추가 |
  | `99b544c`(2026-10-08, 6A) | `platform-spring`의 `com.ga.platform.spring.security.OidcResourceServerConfiguration`(프로파일 `oidc` 골격) 삭제, Spring Security `compileOnly` 의존 제거 | **삭제** |

- 이 머신의 `~/.m2/repository/com/ga/platform/*/0.1.0`은 2026-10-08의 `publishToMavenLocal` 결과라 `OrgPath`가 있고 `OidcResourceServerConfiguration`이
  없다. 발행본은 그 반대다. 좌표가 같은 두 아티팩트이므로 지시문의 "`mavenLocal()` 우선"을 그대로 쓰면 포털이 로컬에서는 발행되지 않은 API로
  컴파일되고, C9(발행본에서 해석)의 CI에서만 다르게 된다.
- 이것은 ga-disclosure의 결함이다: 플랫폼 소스가 바뀌어도 버전을 올리게 하는 검사가 없었다(`checkPlatformVersion`은 태그와 모듈 버전의 일치만 본다).
- 결정(§0): 0.2.0 발행 + 공개 API 덤프 검사 + 포털의 jar SHA-256 고정.

### H2. `envelope.schema.json`은 범용 Envelope가 아니다

- `contracts/events/v1/envelope.schema.json`은 **확인서 이벤트 군의 봉투**다. Phase 0부터 그랬다(`git show phase-0:contracts/events/v1/envelope.schema.json`).
  - `type`은 열거 10개(`DisclosureCreated`·`DisclosureSealed`·`SignatureCaptured`·`DisclosureCompleted`·`DisclosureVoided`·`DisclosureSuperseded`·`PolicyLinked`·`ComplianceFlagRaised`·`DisclosureDestroyed`·`DisclosureAbandoned`).
  - `aggregate.kind`는 열거 `DISCLOSURE`·`COMPLIANCE_FLAG`.
  - `allOf`의 `if/then`이 `type`·`version`별로 `payloads/*.schema.json`을 고른다(11개 — `PolicyLinked`는 v1·v2).
  - `eventId`·`occurredAt`은 `defs.schema.json`을 `$ref`한다.
- 그래서 지시문 §5·C7처럼 이 파일 **하나**를 사본으로 들이면 `$ref`가 풀리지 않고, 엔진 이벤트(포털 §4.1 예시의 `CommissionCalculated`·`COMM_CALC`)는 `type`·`kind` 열거에서 실패한다.
- 공유되는 것은 **필드 집합과 규칙**(아래 §3)이지 이 파일이 아니다. ga-disclosure는 Phase 1에서 이 필드 집합을 포털 §4.1 원문과 맞췄고(`433db53`), 시험은
  `disclosure-rules`의 `ContractSchemaTest.envelopeFollowsPortalConvention`(필드 집합 정확 일치)이다.
- 결정(§0): 핵심 `envelope-core.schema.json` 분리. 핵심의 필드는 정비 지시문이 적은 목록(`schemaVersion`·`tenantId` 포함)이 아니라 §4.1의 일곱 필드다 —
  Envelope에는 `version`이 있고 테넌트가 없으며 `schemaVersion`은 피드 응답의 필드다. 그 목록대로면 기존 이벤트가 전부 핵심에서 실패한다(정비 보고서에 지시문 오류로 기록).
  - 핵심: `required` 일곱 개, `eventId` UUID 정규식, `seq`·`version` 정수 ≥ 1, `type` `^[A-Z][A-Za-z0-9]{0,63}$`, `occurredAt` 오프셋 포함 시각 정규식,
    `aggregate{kind ^[A-Z][A-Z0-9_]{0,31}$, id 비지 않은 문자열}`, `payload` 객체. 모르는 필드는 허용(소비자는 무시). 형식 정규식은 `defs`와 같다(시험이 대조).
  - 포털 §4.1 예시 모양(`CommissionCalculated`·`COMM_CALC`)은 핵심을 통과하고 disclosure envelope는 통과하지 못한다(`ContractSchemaTest.anEngineEventPassesTheCoreButNotTheDisclosureEnvelope`).

### H3. 사본에 `format`이 있다

- `contracts/events/v1/defs.schema.json`의 `instant`·`date`는 `pattern`과 함께 `"format": "date-time"`·`"format": "date"`를 가진다(2건). 강제는 `pattern`이
  하고 `format`은 주석 구실이다(5 D14 이전의 흔적).
- 결정(§0): ga-disclosure에서 지웠다. 시험 범위를 넓히자 룰 번들(2)·룰 버전(1)·증거 매니페스트(1) 스키마의 4개가 더 나왔다(모두 `pattern`이 함께 있었다).
  포털이 가져가는 핵심에는 `format`이 없다.

### H4. `verifyUpstreamContract`를 "엔진 규약 그대로" 쓰면 포털에서 실패한다

- 엔진의 태스크(`ai-comm/commission-system/build.gradle.kts:111`)는 `contracts/` 아래 **모든 파일**(`CHECKSUMS`·`UPSTREAM` 제외)을
  `raw.githubusercontent.com/<owner/repo>/<commit>/contracts/<같은 경로>`와 바이트 대조한다. 엔진은 `contracts/`에 사본만 있어서 성립한다.
- 포털은 자기 정본(payload 13·`engine-internal.openapi.yaml`)도 `contracts/`에 두므로 그대로 가져가면 그 파일들이 업스트림에 없어 실패한다.
  사본을 하위 디렉터리나 닫힌 목록으로 한정해야 한다 — 결정(§0): `contracts/shared/ga-disclosure/`.
- `UPSTREAM` 형식은 `owner/repo@<40자 커밋>` 한 줄이다. 엔진의 현재 값 `hjryoo-ai/ga-disclosure@688cc46…`은 PR #11의 병합 커밋이다.
  포털은 핵심이 들어간 병합 커밋(정비 PR #15의 병합)을 고정한다.

### H5. `CHECKSUMS`의 형식

- ga-disclosure와 엔진의 `contracts/CHECKSUMS`는 **파일 바이트의 SHA-256**, `sha256sum` 형식(`<hex>  <경로>`), 경로 정렬이다
  (`build.gradle.kts:157`, 엔진 `build.gradle.kts:73`). 저장소 사이 대조와 `sha256sum -c`가 이 형식에 기대고 있다.
- 지시문 v1.1 §5의 "JCS로 정규화한 해시를 CHECKSUMS에"는 이 형식과 달랐다(v1.2에서 오기로 정정). 결정(§0): `CHECKSUMS`는 바이트 해시 그대로,
  JCS는 이벤트 payload의 정규 해시에만.

### H6. RLS 세션 설정 이름 — 상수는 공개돼 있지 않다

- 이름은 `app.tenant_id`다(v1.0과 같다). `TenantSessionBinder`가 트랜잭션 시작 때
  `SELECT set_config('app.tenant_id', ?, true)`를 실행한다(트랜잭션 로컬).
- 0.1.0에서는 그 SQL 상수 `SET_TENANT_SQL`이 **package-private**이었고 공개 상수가 없었다. 결정(§0): 0.2.0의 `TenantSession.SETTING_NAME`.
  ga-disclosure의 대조 시험 `RlsSettingNameTest`는 마이그레이션이 읽거나 쓰는 설정 이름이 그 상수이거나 닫힌 표식 목록뿐임을 본다 — 철자가 틀린 테넌트 설정은
  정책이 늘 NULL을 읽어 오류 없이 0행이 되므로 C2만으로는 "이름이 같다"를 증명하지 못한다.

### 확인된 전제 (그대로 맞다)

| 지시문의 인용 | 확인 |
|---|---|
| ga-disclosure Phase 0 수용 심사 R5(이 저장소의 `platform-*`·Envelope가 기준 구현) | 2026-09-29 심사 원문(세션 기록 — 이 심사는 저장소에 보관되기 전이다) |
| 6A 계획 승인 Q5(정수 `afterSeq`·`nextSeq`·`headSeq`) | `docs/설계서.md` §7(v1.13) |
| 5 D14·E3.2(`format` 대신 `pattern`) | `docs/phase-05-보고서.md` D14, `docs/DECISIONS.md` |
| `no-docker`는 호스트 러너에서 데몬을 멈춘다 | `.github/workflows/ci.yml`의 `no-docker` 잡 |
| PostgreSQL 18 digest 고정 | `postgres:18.6`, `disclosure-infra/src/testFixtures/java/com/ga/disclosure/infra/testing/PostgresHarness.java:30` |
| 시드 고정 생성기 공유 | `platform-core`의 test-fixtures 변형(`SeededCases`)으로 발행(0.2.0도) — `testImplementation(testFixtures("com.ga.platform:platform-core:0.2.0"))` |

## 2. 플랫폼 공개 API (`0.2.0` — 정본 `platform/API-0.2.0.txt`)

정본은 ga-disclosure의 `platform/API-0.2.0.txt`(javap 덤프 — 클래스·public·protected 멤버·상수 값·자원 해시)이고 아래 표는 그 요약이다.
0.1.0 발행본과 다른 것은 "0.1.0 발행본" 열에 적었다. 지시문 작업 2·3·4의 분류는 마지막 열.

| 모듈 | 타입 | 공개 멤버 | 0.1.0 발행본 | 포털 분류 |
|---|---|---|---|---|
| core | `money.Won(long value)` record, `Comparable` | `ZERO`, `of`, `plus`, `minus`, `negate`, `isNegative`, `isZero`, `isGreaterThan`, `isLessThan`, `compareTo` | 같음 | 플랫폼에서 |
| core | `money.Ratio(long numerator, long denominator)` record | `of`, `toPercentString`(소수 1자리) — `Won` 생성 없음 | 같음 | 플랫폼에서 |
| core | `time.Ym(int year, int month)` record, `Comparable` | `of`, `parse`(6자리 `yyyyMM`), `next`, `prev`, `range(from, to)`, `value` | 같음 | 플랫폼에서 |
| core | `tenant.TenantId(String value)` record | `of` — 형식 `[A-Z0-9][A-Z0-9_]{0,31}`(소문자·하이픈 불가) | 같음 | 플랫폼에서 |
| core | `tenant.AgentId(String value)` record | `of` | 같음 | 플랫폼에서 |
| core | `tenant.TenantContext` | `current()`(미바인딩 `TenantNotBoundException`), `isBound()`, `runWith(TenantId, Runnable)`, `runWith(TenantId, Callable)` — `ScopedValue` | 같음 | 플랫폼에서 |
| core | `tenant.TenantNotBoundException` | | 같음 | 플랫폼에서 |
| core | `tenant.OrgPath` record | `of`, `contains`(세그먼트 단위 접두) | **없음**(0.2.0에서 추가) | 포털 Phase 0 범위 밖(관리자 범위 판정에 쓸 수 있다) |
| core | `arch.ArchRules` | `noDoubleOrFloat`, `springFreeModules`, `dbAccessOnlyVia`, `layeredDependencies`(`Layer`), `typesOnlyUsedIn`, `methodOnlyInvokedFrom`, `notComparable`, `noOrderingInClassesUsing`, `noClassNameContaining`, `staleClasses`/`stalePackages`(허용 목록 폐기 항목 검사), `Allowed(fqn, reason)` | 같음 | 플랫폼에서(archunit은 소비자가 선언 — `compileOnly`) |
| core test-fixtures | `testing.SeededCases` | `of(seed, gen)`, `of(seed, count, gen)`, `effectiveSeed`, `generator`, `DEFAULT_COUNT = 500`, `ALGORITHM = "L64X128MixRandom"`, `SEED_OVERRIDE_ENV = "GA_SEEDED_CASES_SEED"` | 같음 | 플랫폼에서 |
| canonical | `Canonicalizer` | `canonicalize(String)`, `canonicalize(JsonNode)`, `parseStrict(String)` — RFC 8785, Jackson 3 `JsonNode` | 같음 | 플랫폼에서 |
| canonical | `Sha256` | `of(byte[])`, `ofCanonical(String)` | 같음 | 플랫폼에서 |
| spring | `jdbc.TenantSessionBinder extends JdbcTransactionManager` | 생성자(`DataSource`), `boundTenant()` — 트랜잭션 시작 때 `app.tenant_id` 설정, 컨텍스트 없으면 시작 실패 | 같음 | 플랫폼에서(C4는 포털 시험으로 재증명) |
| spring | `jdbc.TenantScopedRepository`(추상) | `TENANT_PARAM = "tenantId"`, 생성자(`TenantJdbcGateway`), `query`, `queryAtMostOne`, `update` — SQL에 `:tenantId` 술어가 없으면 `MissingTenantPredicateException`, 2행 이상이면 `AmbiguousResultException` | 같음 | 플랫폼에서(구체 저장소 2개는 포털) |
| spring | `jdbc.TenantJdbcGateway` | 생성자(`DataSource`) — 저장소 하위 클래스에 `JdbcClient`를 노출하지 않는 봉투. 바인딩 검사는 저장소 호출 때 `TenantSessionBinder`가 한다: 바인딩된 트랜잭션 밖이면 `OutsideTenantTransactionException`, 테넌트 불일치 `TenantMismatchException` | 같음 | 플랫폼에서(`dbAccessOnlyVia` 허용 목록에 FQN) |
| spring | `jdbc.PlatformJdbcAutoConfiguration` | 빈 `transactionManager`(`TenantSessionBinder`), `tenantJdbcGateway` | 같음 | 플랫폼에서 |
| spring | `jdbc.TenantDirectoryReader` | 생성자(url, user, password), `allTenants()` — 테넌트 목록 전용 롤용 | 같음 | 포털 Phase 0 범위 밖 |
| spring | `identity.IdentityResolver` 인터페이스 | `resolve(oidcSubject) → Optional<ResolvedIdentity(AgentId, Set<String> roles, String orgPath)>` — 구현 없음 | 같음 | 포털 Phase 3(웹 바인딩과 함께) |
| spring | `jdbc.TenantSession` | `SETTING_NAME = "app.tenant_id"` | **없음**(0.2.0에서 추가) | 플랫폼에서(C10 대조) |
| spring | ~~`security.OidcResourceServerConfiguration`~~ | 0.2.0에 없다 | 있음(프로파일 `oidc` 골격) | — 보안 체인은 소비 애플리케이션 몫 |

지시문 v1.0 정의 가운데 플랫폼에 **없는** 것은 찾지 못했다(`Ym.range` 포함 전부 있다).

## 3. 피드 규약

### 포털 설계서 v1.1 §4.1 발췌 (ga-disclosure Phase 0 수용 심사 §4로 받은 원문 그대로)

```
GET /internal/v1/events?afterSeq={n}&limit={≤1000}
→ { "events": [Envelope...], "nextSeq": n', "headSeq": h, "schemaVersion": 1 }
- 응답 순서는 seq 오름차순, 갭 없음. headSeq − nextSeq가 소비자의 지연 지표.
- 인증: 서비스 토큰. 사용자 토큰으로는 접근 불가.

Envelope
{
  "eventId": "6f1c…",            // UUID, 멱등키 (UNIQUE)
  "seq": 10231,                  // 발행자(테넌트 인스턴스) 내 단조 증가 정수
  "type": "CommissionCalculated",// 이벤트 타입명
  "version": 1,                  // 스키마 버전(정수). 필드 추가는 호환, 삭제·의미 변경·타입 변경은 version 증가
  "occurredAt": "2026-09-22T10:15:30+09:00",   // ISO-8601 오프셋 포함
  "aggregate": { "kind": "COMM_CALC", "id": "884213" },
  "payload": { ... }
}
호환성: 소비자는 모르는 필드를 무시한다. 지원하는 최대 version보다 큰 이벤트를 만나면 그 테넌트의 소비를 정지하고 운영 알림.
seq 규칙(소비자): 기대값(last+1)보다 작으면 무시, 같으면 적용, 크면(갭) 정지.
```

### ga-disclosure가 이 규약 위에 더한 것 (정본 `docs/event-feed.md`, `contracts/api/v1/disclosure-internal.openapi.yaml`)

- 소비자는 서비스 주체다: 토큰(`sub`·`tenant_id`)의 주체가 `identity_link`에서 역할 `FEED_CONSUMER`로 연결돼 있어야 한다. 사람 역할이 부르면 404.
- `limit` 1~1000, 기본 100. `afterSeq` 생략 = ack한 지점부터. `afterSeq > headSeq`면 422 `AFTER_BEYOND_HEAD`(다른 발행자를 보고 있다 — 소비 정지·알림).
- ack: `POST /internal/v1/events/ack` `{"upToSeq": n}` + `Idempotency-Key` → `{"ackedSeq", "headSeq"}`. `upToSeq > headSeq`면 422 `ACK_BEYOND_HEAD`.
- 전달 보장 at-least-once: ack 전에 다시 읽으면 같은 이벤트가 다시 온다. 중복 제거는 `eventId`.
- 추가형 이벤트(소비자가 알아야 할 것):

  | 이벤트 | 버전 | 시작 | 소비자가 할 일 |
  |---|---|---|---|
  | `DisclosureDestroyed` | 1 | Phase 5 | 복제해 둔 개인정보 삭제, 그 확인서의 본문·PDF 링크를 더 내지 않음 |
  | `DisclosureAbandoned` | 1 | 6B | 봉인 전 초안에 대해 복제한 자유 텍스트 삭제 |
  | `PolicyLinked` | **2만 발행** | 6B | 증권·청약 번호 없음. v1 스키마는 계약에 남지만 발행된 적 없다. 연결이 다른 확인서로 옮겨질 때도 받는 확인서로 발행 |

- payload에는 고객 개인정보·사유 텍스트·서명 토큰·수수료율이 없다. 고객은 가명 참조 `customerRef`뿐이다.

## 4. IdP·신원 규약

- 테넌트는 토큰의 `tenant_id` 클레임 하나에서 온다. `agent_id`·역할·조직은 클레임이 아니라 `identity_link`(테넌트, OIDC subject → 설계사·역할·조직)
  조회 결과다. 역할 코드는 데이터(문자열 집합)이고 열거형이 아니다. 플랫폼 계약은 `IdentityResolver`(§2) — 구현은 소비 애플리케이션이 한다
  (ga-disclosure는 `disclosure-api`의 보안 체인에서).
- ga-disclosure의 인증 실패는 전부 같은 401 본문이고, 주체가 다른 테넌트 자원을 가리키면 없는 자원과 같은 404다(설계서 §7).

## 5. 계약 사본 규약 (엔진 E3~E3.2에서 정한 것)

- 사본은 고치지 않는다. 바꿔야 하면 ga-disclosure에서 작은 PR → 병합 → 소비 저장소의 `UPSTREAM`을 그 **병합 커밋**으로 옮기고 사본을 다시 받는다.
- 표기 변경(의미 변경 없음) PR은 지시문이 명시한 경우에만 자체 CI 통과 뒤 병합할 수 있다(상대 저장소의 `UPSTREAM`이 병합 커밋을 가리켜야 하므로).
- `verifyContractChecksums`(오프라인, `check`에 연결)와 `verifyUpstreamContract`(네트워크, CI 전용)를 나눈다 — 엔진 `build.gradle.kts:73~133`.
- 포털(H4·H5 결정): 사본은 `contracts/shared/ga-disclosure/`에만 두고(지금은 `events/v1/envelope-core.schema.json` 하나), 그 디렉터리 전용 `CHECKSUMS`
  (평문 SHA-256, `sha256sum` 형식)와 `UPSTREAM`(`hjryoo-ai/ga-disclosure@<병합 커밋 40자>` + 파일 목록)을 둔다. `verifyUpstreamContract`는 그 디렉터리만
  `raw.githubusercontent.com/hjryoo-ai/ga-disclosure/<커밋>/contracts/<업스트림 경로>`와 바이트 대조한다.

## 6. GitHub Packages 소비 — 확인할 것

- 0.2.0 발행본의 SHA-256은 발행 워크플로(`publish-platform.yml`) 로그의 두 곳이 1차 출처다: 올린 파일(jar·sources·test-fixtures·POM·모듈 메타데이터)의
  `sha256sum`, 그리고 `rm -rf ~/.m2/repository/com/ga` 뒤 GitHub Packages에서 다시 받은 jar의 `PLATFORM_SHA256` 줄. 값은 정비 보고서에 옮긴다.
  Gradle 의존성 검증은 jar뿐 아니라 POM·`.module`도 대조하므로 포털의 메타데이터에는 그 셋을 다 넣는다.

- 저장소 URL `https://maven.pkg.github.com/hjryoo-ai/ga-disclosure`. ga-disclosure의 소비 예시는 `verification/published-consumer/settings.gradle.kts`
  (`exclusiveContent`로 `com.ga.platform` 그룹을 한 저장소에만, 자격은 환경변수 `GITHUB_ACTOR`·`GITHUB_TOKEN`).
- GitHub Packages의 Maven 저장소는 공개 패키지도 인증을 요구한다. 다른 저장소(포털)의 Actions `GITHUB_TOKEN`으로 읽으려면 패키지 설정에서 그 저장소에
  읽기 권한을 줘야 하는 것으로 알고 있다 — **직접 확인하지 않았다.** C9의 CI가 첫 실행에서 이것을 확인한다.
- 이 머신의 `gh` 토큰에는 `read:packages`가 없어 패키지 목록 API가 403이다. "0.1.0 뒤 재발행이 있었는지"는 태그(`platform-v*` 하나)와 발행 워크플로
  실행 이력으로만 확인했다.
