# Phase 0 지시문 — ga-disclosure 골격·플랫폼 공유 (v1.1)

> v1.1 (2026-09-28): 사전 확인 보고에 대한 결정 반영 — ① `ratio_to_avg` TEXT ② 불변 트리거 3건 강화 ③ jqwik 제거·차단, JUnit 6 파라미터화 + 시드 고정 생성기 ④ Boot BOM 관리 버전 준수(Flyway 12.4.0). 설계서 v1.3과 동기.

## 역할과 맥락

당신은 `ga-disclosure` 저장소의 구현자다. 이 저장소는 대형 GA 설계사가 계약 체결 전에 **동종·유사상품 3개 이상 비교표 → 판매수수료 등급·순위 자동 표기 → 추천사유 → 고객·설계사·관리자 확인(서명) → 불변 보관·감사**를 끝내는 멀티테넌트 워크플로 서비스다. 등급·순위의 산출은 별도 저장소 `ga-commission-engine`이 담당하고, 테넌시·인증 규약은 `ga-agent-portal`과 공유한다. 정본 문서는 `docs/설계서.md`(첨부한 설계서 v1.2)와 저장소 루트 `CLAUDE.md`(첨부)이며, 이번 Phase는 설계서 **§0, §1.5, §3.1~3.3, §4.1(계약 골격), §5, §9, §11, §12 Phase 0, §13**만 대상으로 한다.

Phase 0의 목적은 업무 기능이 아니라 **이후 모든 Phase가 어길 수 없는 구조적 규약을 먼저 코드와 DB로 못 박는 것**이다. 이 Phase가 끝나면 다음 네 가지가 코드로 불가능해져야 한다.

1. `tenant_id` 조건 없는 데이터 접근
2. 워크플로 안에서 수수료율·비율을 숫자로 다루는 연산
3. 봉인된 확인서 본문의 UPDATE·DELETE, 문서 해시와 불일치하는 서명 INSERT, 기간이 겹치는 활성 룰 버전 INSERT
4. `double`/`float` 사용

## 불변 규약 (CLAUDE.md와 동일, 위반 시 수용 거부)

CLAUDE.md의 절대 규칙 1~8과 코드 규약을 그대로 따른다. 이번 Phase에서 특히 다음을 코드·DB·테스트로 구현한다: 규칙 1(수수료율 연산 금지 → 아키텍처 테스트), 2·3(불변·해시 귀속 → DB 트리거), 4(룰 겹침 → 배타 제약), 5(테넌트 격리 → 컨텍스트·저장소·RLS).

## 사전 확인 (작업 시작 전 보고)

1. `ga-agent-portal` 저장소의 Phase 0 산출물(`portal-domain`의 `Won`/`Ratio`/`Ym`/`TenantId`/`AgentId`/`TenantContext`, `portal-infra`의 `TenantSessionBinder`/`TenantScopedRepository`, 아키텍처 테스트)이 접근 가능한 경로에 있는지 확인한다. 있으면 **이식**, 없으면 아래 §2·§3 규격으로 **신규 작성**한다. 어느 쪽인지 계획에 명시한다.
2. Java 25, Spring Boot 4.1.x, PostgreSQL 18, ArchUnit, btree_gist 확장의 **현재 최신 안정 버전**을 확인해 버전 카탈로그에 고정한다. 추측 금지. 단, **Spring Boot BOM이 관리하는 라이브러리(Flyway·JUnit·Testcontainers·AssertJ·Jackson·PostgreSQL 드라이버 등)는 BOM 버전을 그대로 쓴다.** 최신판이 더 높아도 덮어쓰지 않는다(메이저 상향은 Boot 자동설정 호환이 보장되지 않는다). 상향이 꼭 필요하면 사유를 계획에 적고 승인 후 진행한다. 보고서에는 "BOM 관리 버전 준수" 항목으로 실제 해석된 버전(예: Flyway 12.4.0, JUnit 6.0.x)을 남긴다.
3. 위 두 가지를 포함한 구현 계획을 먼저 보여주고, 승인 후 진행한다.

## 작업 목록

### 1. 저장소·빌드 골격
- Gradle(Kotlin DSL) 멀티모듈. `settings.gradle.kts`에 등록: `platform-core`, `platform-spring`, `disclosure-domain`, `disclosure-rules`, `disclosure-workflow`, `disclosure-seal`, `disclosure-sign`, `disclosure-audit`, `disclosure-compliance`, `disclosure-api`, `disclosure-infra`, `disclosure-app`, `disclosure-demo`. `web/`은 디렉터리와 README 한 줄만(Phase 7). `contracts/`, `docs/` 생성.
- 툴체인: Java 25(Gradle toolchain 고정), Spring Boot 4.1.x 최신 패치. 버전은 `gradle/libs.versions.toml`, 의존성 락(`--write-locks`) 활성화. BOM 관리 라이브러리는 카탈로그에 버전을 적지 않고 BOM에 위임한다.
- **의존성 차단**: 루트 빌드의 `configurations.all { resolutionStrategy.eachDependency { … } }`에서 그룹 `net.jqwik`이 직접·전이 어느 경로로든 해석되면 빌드를 실패시킨다(사유 메시지에 CLAUDE.md 규약 번호 인용). 이유: jqwik 1.10부터 AI 코딩 에이전트 사용을 배제하는 조항과 함께 테스트 실행 출력에 에이전트 대상 지시문을 삽입하며, JUnit Platform 1.14를 요구해 Boot 4의 JUnit 6과도 비호환이다. `./gradlew dependencies`에 `net.jqwik`이 없음을 CI가 검사한다.
- 의존 방향 강제: `app → api → workflow/compliance → rules/seal/sign/audit → domain → platform-core`. `platform-spring`은 `infra`·`api`·`app`만 의존 가능. `infra`는 `app`만 의존 가능. `platform-core`·`domain`·`rules`·`seal`(렌더러 하위 패키지 제외)은 컴파일 클래스패스에 `spring-*`, `java.sql`, `javax.sql`을 갖지 않는다.
- `platform-core`·`platform-spring`은 `maven-publish`로 `com.ga.platform:platform-core:0.1.0`, `com.ga.platform:platform-spring:0.1.0`을 `publishToMavenLocal`할 수 있게 한다(포털의 소비는 후속 PR, 이번 범위 밖).
- `disclosure-app`: Spring Boot 진입점 + `/actuator/health`만. 컨트롤러·비즈니스 로직 작성 금지.
- 루트 `README.md`: 한 문단 소개 + 면책(CLAUDE.md 면책 문단 그대로) + 실행 방법(`./gradlew build`, Docker 필요) + 모듈 표. `LICENSE`는 MIT. `docs/설계서.md`·`CLAUDE.md`·`docs/phase-00-지시문.md`에 첨부 파일을 그대로 넣는다.

### 2. 플랫폼 공유 모듈 (`platform-core`, `platform-spring`)
포털 Phase 0 산출물이 있으면 패키지만 `com.ga.platform.*`로 옮겨 이식하고, 없으면 아래 규격으로 작성한다.

**`platform-core` (Spring·DB 무의존)**
- `Won`: `long` 기반 정수 원. `plus`·`minus`·`negate`·`isNegative`·`ZERO`·비교. **곱셈·나눗셈 없음.** 오버플로는 `Math.addExact` 계열로 예외. `Ratio`(분자·분모 `long`, 백분율 소수 1자리 문자열만 제공)는 `Won`을 만들 수 없다.
- `Ym`(YYYYMM 검증, `next`·`prev`·`compareTo`·`range`), `TenantId`, `AgentId`. 전부 `record`, 생성자 검증.
- `TenantContext`: JDK 25 `ScopedValue` 기반. `current()`는 바인딩이 없으면 `TenantNotBoundException`(null 반환 금지). `runWith(tenantId, Runnable/Callable)`. 비동기·스케줄러 실행 시 명시적 재바인딩 필요를 Javadoc에 명시.
- `ArchRules`: ArchUnit 규칙을 **재사용 가능한 라이브러리**로 제공 — `noDoubleOrFloat()`, `springFreeModules(...)`, `dbAccessOnlyVia(TenantScopedRepository)`, `layeredDependencies(...)`. 포털과 본 저장소가 같은 규칙 객체를 쓰게 하는 것이 목적.

**`platform-spring` (Spring 의존)**
- `TenantSessionBinder`: 트랜잭션 시작 시 `SET LOCAL app.tenant_id = ?` 실행. 값은 반드시 `TenantContext.current()`에서 가져오며 컨텍스트가 없으면 트랜잭션 자체가 예외로 실패한다(SET을 건너뛰고 진행 금지). 트랜잭션 밖의 DB 접근은 금지하고 테스트한다.
- `TenantScopedRepository` 추상 기반 클래스(Spring Data JDBC `JdbcClient` 래핑): 모든 조회·변경 메서드가 `TenantContext.current()`를 `tenant_id` 바인드 변수로 자동 주입한다.
- `IdentityResolver` 인터페이스만(OIDC subject → `identity_link` 조회 → `{agentId, roles, orgPath}`): 구현·요청 필터는 Phase 6(API·인가)에서. OIDC 리소스 서버 설정 클래스는 골격만 두고 프로파일로 비활성.

### 3. 도메인 값객체·열거형 (`disclosure-domain`)
- 값객체(전부 `record`, 생성자 검증): `DisclosureId`(UUID), `DisclosureNo`(패턴 `{tenant}-{yyyy}-{6자리}`), `ProductKey`(`{insurerCode}:{code}`), `InsurerCode`, `GroupCode`, `CustomerRef`, `RuleVersionId`, `TemplateRef(templateId, version)`, `Sha256`(64 hex 검증, `equals`는 상수 시간 비교), `SnapshotId`, `ChainHash`.
- **`GradeSnapshotItem`**: `productKey`, `status(OK|UNAVAILABLE)`, `gradeCode`(String), `gradeLabel`(String), `gradeOrdinal`(int, 엔진 제공), `rankInSet`(int), `tie`(boolean), `ratioToAvg`(**`RatioLabel`**). `RatioLabel`은 `String value` 하나만 가진 record이며 숫자 변환·비교·`Comparable`을 제공하지 않는다. `GradeSnapshotItem`도 `Comparable`이 아니다. 등급 코드·개수·의미는 도메인이 알지 못한다(열거형 금지).
- 열거형: `DisclosureStatus{DRAFT, COMPARED, GRADED, REASONED, SEALED, PARTIALLY_SIGNED, COMPLETED, VOID, SUPERSEDED, EXPIRED}` + `isMutable()`(DRAFT·COMPARED·GRADED·REASONED만 true) + `isSealedOrLater()`. `SignerRole{CUSTOMER, AGENT, MANAGER}`, `SignatureChannel{TOUCH_PAD, REMOTE_LINK, PAPER_SCAN, CERTIFIED_ESIGN}`, `IssuerMode{SELF, ASSOC}`, `GateMode{BLOCK, WARN, OFF}`, `ManagerConfirmMode{REQUIRED, OPTIONAL, OFF}`, `GradeStatus{OK, UNAVAILABLE}`, `ReconStatus{MATCHED, MISSING, LATE, EXEMPT}`, `RuleStatus{DRAFT, APPROVED, ACTIVE, RETIRED}`, `ArtifactKind{CANONICAL_JSON, PDF, SIGNED_PDF, EVIDENCE_ZIP}`.
- `ReasonCode`는 열거형이 **아니라** 문자열 값객체(룰 데이터에서 온다). 상태 전이표·검증 룰·유스케이스는 Phase 1·3 범위이므로 이번에는 만들지 않는다.

### 4. PostgreSQL 스키마·RLS·불변 트리거 (`disclosure-infra`)
- **Flyway `V1__init.sql`**: 설계서 §5의 모든 테이블(`tenant`, `identity_link`, `rule_version`, `form_template`, `product_group`, `product_catalog`, `insurer_panel`, `customer_ref`, `disclosure`, `disclosure_item`, `recommendation`, `document_artifact`, `sign_session`, `signature`, `audit_log`, `audit_anchor`, `subject_policy`, `compliance_flag`)과 인덱스. `CREATE EXTENSION IF NOT EXISTS btree_gist`. `tenant.params` 기본값에 `{"gateRequiresManager": true}`를 명시. `rule_version`에 배타 제약: `EXCLUDE USING gist (tenant_id WITH =, scope WITH =, daterange(apply_from, apply_to, '[)') WITH &&) WHERE (status = 'ACTIVE')`.
- **`V2__rls.sql`**: `tenant`를 포함한 모든 테넌트 테이블에 `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` + `POLICY tenant_isolation USING (tenant_id = current_setting('app.tenant_id', true))`(미설정 시 NULL → 0행). 롤 분리: 마이그레이션 `disclosure_migrator`(소유자), 애플리케이션 `disclosure_app`(`NOBYPASSRLS`, DML만). `docker/postgres/init-roles.sql`, `docker-compose.yml`(PostgreSQL 18 + MinIO는 Phase 3에서 추가) 제공. 애플리케이션 데이터소스는 `disclosure_app`으로만 접속.
- **`V3__immutability.sql`** (규칙 2·3을 DB가 강제):
  - `disclosure` BEFORE UPDATE: `OLD.status ∉ {DRAFT, COMPARED, GRADED, REASONED}`이면 **본문 컬럼**(`agent_id, customer_ref, group_code, template_id, template_version, rule_version_id, consult_date, grade_snapshot_id, issuer_mode, version, supersedes_id, disclosure_no, sealed_at, canonical_hash, pdf_hash, chain_hash, chain_seq`)의 변경을 거부. **메타 컬럼**(`status, superseded_by_id, completed_at, voided_at, void_reason, policy_no, contract_date, retention_until`)만 허용. `disclosure` DELETE는 항상 거부.
  - `disclosure` BEFORE UPDATE(상태 회귀 금지): `OLD.status`가 봉인 이후 상태(`SEALED, PARTIALLY_SIGNED, COMPLETED, VOID, SUPERSEDED, EXPIRED`)이면 `NEW.status`가 가변 상태(`DRAFT, COMPARED, GRADED, REASONED`)인 UPDATE를 거부. 상태를 되돌린 뒤 본문을 고치는 우회를 막는다. 봉인 이후 상태 간의 전이 규칙(어느 상태에서 어디로)은 Phase 3 상태기계가 담당하므로 DB는 "가변으로 되돌아가지 않는다"만 강제한다. `superseded_by_id`는 `OLD`가 NULL일 때만 값을 쓸 수 있다(write-once).
  - `disclosure_item`·`recommendation` BEFORE INSERT/UPDATE/DELETE: 부모 `disclosure.status`가 위 4개 가변 상태가 아니면 거부(봉인된 문서에 항목을 끼워 넣는 것도 막는다).
  - `signature` BEFORE INSERT: `NEW.signed_doc_hash = (SELECT canonical_hash FROM disclosure WHERE …)`가 아니면 거부. 부모 `status ∈ {SEALED, PARTIALLY_SIGNED}`일 때만 허용 — 봉인 전은 물론 `VOID`·`EXPIRED`·`SUPERSEDED`·`COMPLETED` 문서에 대한 서명도 거부한다. `signature`·`audit_log`·`document_artifact`·`audit_anchor`는 UPDATE·DELETE 항상 거부(append-only). 보존기간 도래 파기는 Phase 5에서 `disclosure_migrator` 전용 함수로 구현하므로 지금은 예외 경로를 두지 않는다.
  - 트리거 함수는 `SECURITY DEFINER`가 아니며, `disclosure_app`은 트리거를 비활성화할 권한이 없다.
- **저장소**: `platform-spring`의 `TenantScopedRepository`를 상속한 구체 저장소 두 개만 구현해 패턴을 확정한다 — `TenantRepository`(자기 테넌트 행 조회), `RuleVersionRepository`(insert·`findActiveOn(scope, date)`). 룰 해석기는 Phase 1이므로 `findActiveOn`은 단순 조회이며 2건이면 예외를 던지는 것까지만 한다.

### 5. 계약 폴더 (`contracts/`)
- `contracts/api/v1/engine-disclosure.openapi.yaml`: `POST /internal/v1/disclosure/commission-grades`(설계서 §4.1, `gradeOrdinal` 포함, `ratioToAvg`는 `string`). 서버 구현은 엔진 저장소 Phase E3.
- `contracts/api/v1/disclosure-internal.openapi.yaml`: `GET /internal/v1/disclosures/gate`(`pendingRoles`, `gateSatisfied` 포함), `POST /internal/v1/disclosures/{no}/policy-link`, `GET /internal/v1/events`.
- `contracts/events/v1/`: `envelope.schema.json` + 설계서 §4.5의 8개 이벤트 payload 스키마(JSON Schema 2020-12) + 타입별 유효 샘플.
- `contracts/rules/v1/rule-version.schema.json`(설계서 부록 D 구조 전체를 스키마화), `form-template.schema.json`(`fields[]{code,label,required,source,order,render}`, `layout`) + 샘플: `DISC-2026-07.json`(부록 D 그대로), `STANDARD-v1.json`(금융위 보도자료 예시에 나온 항목 — 보험회사명·비교상품군·상품명·보험료·해약환급예시·판매수수료등급·판매수수료순위·추천사유·추천가능보험사 — 만 넣고 나머지 필수 항목은 `TODO(confirm#2)`로 표시한 자리만 둔다. **항목명을 지어내지 않는다.**)
- `disclosure-rules`에 스키마 검증 테스트: 모든 샘플이 스키마를 통과하고 필수 필드를 하나 제거한 변형은 실패. `contracts/CHECKSUMS` 생성 Gradle 태스크(엔진·포털 저장소와의 일치 검사용).

### 6. 아키텍처 테스트 (`disclosure-app`의 `archTest` 소스셋)
`platform-core.ArchRules`를 사용해 다음을 강제한다.
- (a) §1의 모듈 의존 방향. (b) Spring·DB 무의존 모듈 목록. (c) 전 모듈 `double`/`float`/`Double`/`Float` 필드·파라미터·반환 금지. (d) DB 접근은 `TenantScopedRepository` 상속 클래스에서만, `JdbcClient`/`JdbcTemplate`/`DataSource` 직접 참조는 `TenantScopedRepository`·`TenantSessionBinder`·Flyway 설정에서만.
- (e) **수수료율 연산 금지(규칙 1)**: ① `java.math.BigDecimal`·`BigInteger` 참조는 `disclosure-infra`의 `..infra.json..` 패키지에서만 허용 ② `RatioLabel.value()` 호출은 `disclosure-seal`의 렌더러 패키지와 `disclosure-api`의 DTO 매퍼 패키지에서만 허용 ③ `GradeSnapshotItem`·`RatioLabel`은 `Comparable`을 구현하지 않고, 이들을 원소로 하는 `Comparator`·`sorted`·`max`·`min` 호출은 `disclosure-rules`의 `GradeConsistencyCheck` 클래스(Phase 3에서 구현, 지금은 빈 final 클래스와 허용 목록만)에서만 허용 ④ 저장소 어디에도 이름에 `Grading`·`Ranking`·`CommissionRate`를 포함한 클래스를 만들 수 없다(엔진 클라이언트 DTO는 `GradeSnapshot*`로 명명).
- SQL 소스 스캔 테스트: `disclosure-infra`의 모든 `.sql`·문자열 SQL을 스캔해 `tenant`, `flyway_schema_history`를 제외한 테이블 접근 문장에 `tenant_id` 조건 또는 컬럼 지정이 포함되는지 검사. 허용 목록은 비어 있는 상태로 시작, 항목 추가 시 사유 주석 강제.

### 7. 테스트 하네스·CI
- Testcontainers PostgreSQL 18(태그 고정), JVM당 1회 기동·재사용. 기동 시 `init-roles.sql` → `disclosure_migrator`로 Flyway → 테스트 데이터소스는 `disclosure_app`. 트리거·제약 테스트는 `disclosure_migrator` 연결로 A·B 두 테넌트 데이터를 심고 `disclosure_app`으로 검증한다.
- 속성 테스트(jqwik 미사용): `platform-core`의 테스트 픽스처(`testFixtures` 소스셋)에 `SeededCases` 유틸을 둔다 — `java.util.random.RandomGenerator.of("L64X128MixRandom")`을 **소스에 상수로 적힌 시드**로 생성하고, `Stream<Arguments>`를 만들어 JUnit 6 `@ParameterizedTest` + `@MethodSource`로 N건(기본 500)을 돌린다. 실패 메시지에 시드와 케이스 인덱스를 포함해 재현 가능하게 한다. 이 유틸로 `Won` 덧셈·뺄셈 결합·교환·오버플로, `Ym.next/prev` 왕복, `DisclosureNo`·`ProductKey` 파서 왕복을 검증한다. 시드를 환경변수로 덮어쓸 수 있되 CI에서는 고정값만 쓴다.
- GitHub Actions(`./gradlew build`, Docker 러너). Docker 없으면 통합 테스트 실패(스킵 금지), README에 요구사항 명시.

## 완료 기준 (전부 테스트로 증명, 클래스명은 제안)

| # | 기준 | 증명 방법 |
|---|---|---|
| C1 | `TenantContext` 미바인딩 상태의 저장소 호출은 DB 도달 전 예외 | `TenantContextGuardTest` |
| C2 | `disclosure_app` 세션에서 `app.tenant_id` 미설정 시 모든 테넌트 테이블 0행, 설정 시 해당 테넌트 행만(`tenant` 테이블 포함) | `RlsIsolationIT` |
| C3 | `disclosure_app`은 RLS를 우회·해제·트리거 비활성화할 수 없음 | `RlsIsolationIT`: `SET ROLE`, `ALTER TABLE … DISABLE ROW LEVEL SECURITY`, `ALTER TABLE … DISABLE TRIGGER`가 권한 오류 |
| C4 | 트랜잭션 밖 DB 접근 거부 | `TenantSessionBinderTest` |
| C5 | 모듈 의존·Spring 무의존·double 금지·저장소 상속·**수수료율 연산 금지 4종** 규칙 전건 통과 | `ArchitectureRulesTest` + 각 규칙을 **의도적으로 위반한 임시 클래스**(예: `ratioToAvg`를 파싱해 정렬하는 클래스)로 실패 확인 후 제거, 보고서에 기록 |
| C6 | SQL 스캔이 `tenant_id` 없는 문장을 잡음 | `TenantPredicateScanTest` + 위반 문장 주입 → 실패 → 제거 |
| C7 | 봉인 상태(`SEALED`·`PARTIALLY_SIGNED`·`COMPLETED`·`VOID`·`SUPERSEDED`·`EXPIRED`) `disclosure` 본문 컬럼 UPDATE·행 DELETE 거부, 메타 컬럼 UPDATE 허용, `DRAFT`~`REASONED`는 UPDATE 허용; 봉인 상태 부모의 `disclosure_item`·`recommendation` INSERT·UPDATE·DELETE 거부; 봉인 이후 상태 → 가변 상태 `status` UPDATE 거부(6×4 전수), 봉인 이후 상태 간 UPDATE는 허용; `superseded_by_id` 두 번째 쓰기 거부 | `ImmutabilityTriggerIT`(상태 10종 × 컬럼군 2종 + 상태 회귀 6×4 매트릭스) |
| C8 | `signed_doc_hash ≠ canonical_hash`인 `signature` INSERT 거부; 부모가 `SEALED`·`PARTIALLY_SIGNED`일 때만 INSERT 허용, 나머지 8개 상태 전부 거부; `signature`·`audit_log`·`document_artifact`·`audit_anchor` UPDATE·DELETE 거부 | `AppendOnlyTriggerIT`(부모 상태 10종 전수) |
| C9 | 같은 scope에서 기간이 겹치는 ACTIVE `rule_version` INSERT 거부 — **개시일이 같은 경우와 다른 경우 모두**; `apply_to` NULL(무기한) 겹침 포함; `DRAFT`·`RETIRED`는 겹쳐도 허용 | `RuleVersionExclusionIT` |
| C10 | 계약 샘플(이벤트 8종·룰 1종·서식 1종)이 스키마를 통과하고 필수 필드 제거 변형은 실패 | `ContractSchemaTest` |
| C11 | `platform-core`·`platform-spring`이 `publishToMavenLocal`로 발행되고, 발행된 아티팩트만으로 `disclosure-domain`이 컴파일됨 | 별도 Gradle 검증 태스크 또는 CI 잡 |
| C12 | `./gradlew build`가 클린 체크아웃 + Docker 환경에서 한 번에 통과 | CI 로그 |
| C13 | `ratio_to_avg`가 DB 왕복 후 원문과 바이트 동일(`"0.84"`→`"0.84"`, `"0.840"`→`"0.840"`, `"1.3700"` 등 정규화 없음) | `RatioLabelRoundTripIT` |
| C14 | `net.jqwik` 의존을 테스트 모듈에 추가하면 빌드가 해석 단계에서 실패 | 임시 추가 → 실패 확인 → 제거, 보고서 기록 |

## 하지 말 것

- 룰 해석기·검증 룰 실행기(Phase 1), 카탈로그·암호화(Phase 2), 상태기계·유스케이스·봉인·PDF(Phase 3), 서명(Phase 4), 감사 체인·앵커(Phase 5), 준법·게이트·이벤트 발행(Phase 6), 프론트(Phase 7)의 구현.
- 엔진 저장소 수정(Phase E3은 별도 지시), 포털 저장소 PR.
- Kafka 등 브로커, Redis, 캐시 계층, 오브젝트 스토리지 어댑터(Phase 3) 도입.
- 설계서에 없는 테이블·컬럼·상태 추가. 필요하면 먼저 보고.
- 서식 항목명·등급 코드 목록·추천사유 코드를 코드에 상수로 두는 것. 샘플 데이터에도 확인되지 않은 항목명을 지어 넣는 것.

## 보고 형식

1. Git 태그 `phase-0`, 커밋 목록(한 줄 요약), 변경 파일 트리.
2. 사전 확인 결과: 포털 산출물 이식 여부, 고정한 버전 목록과 확인 출처.
3. 테스트 요약: 전체 건수, 모듈별 건수, 통합 테스트(Testcontainers) 건수.
4. 완료 기준 C1~C14 각각의 증거(테스트 클래스·메서드명). C5·C6·C14는 위반 주입 → 실패 → 제거 기록 포함.
5. 설계서와 달리 구현한 지점과 이유(없으면 "없음").
6. 다음 Phase(1 룰·서식 데이터, E3 엔진 등급·순위 API)를 위해 확인이 필요한 질문.
