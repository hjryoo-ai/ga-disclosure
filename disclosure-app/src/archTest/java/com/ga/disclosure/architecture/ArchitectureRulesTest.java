package com.ga.disclosure.architecture;

import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.grade.RatioLabel;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.rules.grade.GradeConsistencyCheck;
import com.ga.platform.core.arch.ArchRules.Allowed;
import com.ga.platform.core.arch.ArchRules.Layer;
import com.ga.platform.core.arch.ArchRules;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C5: 모듈 의존·Spring 무의존·double 금지·저장소 상속·수수료율 연산 금지 4종. 규칙 정의는 platform-core {@link ArchRules}
 * (포털과 공유)이고, 이 클래스는 ga-disclosure의 패키지·허용 목록으로 규칙을 구성할 뿐이다.
 * 검사 대상은 전 모듈의 운영 클래스({@code com.ga..})이며 테스트·테스트 픽스처·이 소스셋은 제외한다.
 *
 * <p><b>허용 목록</b>은 패턴이 아니라 FQN 열거이며 항목마다 사유를 적는다(CLAUDE.md 작업 방식). 규칙 테스트가 거짓 양성을
 * 내면 목록을 넓히지 않고 코드를 옮긴다. 목록을 넓힐 때는 이 소스에서 사유와 함께 넓히고 보고서에 기록한다.
 * {@link #allowlistsHaveNoStaleEntries()}가 폐기 항목(존재하지 않는 클래스·패키지)을 잡는다.
 */
class ArchitectureRulesTest {

    static final String P = "com.ga.disclosure.";

    // ------------------------------------------------------------------ 허용 목록 (FQN, 사유)

    /** DB 접근의 정식 경로: 이 클래스의 하위 클래스만 JDBC 패키지를 쓸 수 있다(CLAUDE.md 절대 규칙 5). */
    static final Allowed REPOSITORY_BASE = new Allowed("com.ga.platform.spring.jdbc.TenantScopedRepository",
            "모든 저장소의 기반 클래스 — :tenantId 강제·컨텍스트 주입·바인더 트랜잭션 대조 후에만 JdbcClient를 호출한다");

    /** 원시 JDBC 진입점(DataSource·JdbcClient 등)을 만질 수 있는 기반 클래스 외 인프라. */
    static final List<Allowed> DB_INFRASTRUCTURE = List.of(
            new Allowed("com.ga.platform.spring.jdbc.TenantJdbcGateway",
                    "DataSource로 JdbcClient를 만들어 저장소 기반 클래스에만 건네는 봉투(하위 저장소에는 노출하지 않는다)"),
            new Allowed("com.ga.platform.spring.jdbc.TenantSessionBinder",
                    "트랜잭션 시작 시 set_config('app.tenant_id')로 RLS 세션 값을 넣는 트랜잭션 매니저"),
            new Allowed("com.ga.platform.spring.jdbc.PlatformJdbcAutoConfiguration",
                    "애플리케이션 DataSource로 위 두 빈을 조립하는 자동 구성"),
            new Allowed("com.ga.platform.spring.jdbc.TenantDirectoryReader",
                    "운영자 CLI --tenants all 전용 테넌트 ID 목록 — tenant.tenant_id만 읽을 수 있는 disclosure_operator 롤로 별도 접속"
                            + "(Phase 1 계획 D4, 설계서 §9)"),
            new Allowed(P + "infra.retention.DestroyerGateway",
                    "파기 함수 3개 호출 — 호출자 트랜잭션의 연결에서 SET LOCAL ROLE disclosure_destroyer(전용 롤, 함수 EXECUTE만) → 함수 → RESET ROLE. "
                            + "테넌트 데이터를 읽지 않는다(5 계획 승인 Q2, 설계서 §9)"),
            new Allowed(P + "infra.jobs.JobLockGateway",
                    "작업 잠금 — 전용 롤 disclosure_job_lock(테이블·스키마 권한 0, V12 단언)으로 풀 없이 연 커넥션의 세션 advisory lock과 "
                            + "pg_locks 보유 확인만. 테넌트 데이터를 읽지 않는다(6A 계획 §6.1, 승인 Q8·B1)"));

    /** BigDecimal·BigInteger 참조 허용 패키지(CLAUDE.md 절대 규칙 1: JSON 매핑 외 참조 금지). */
    static final List<Allowed> BIG_NUMBER_PACKAGES = List.of(
            new Allowed(P + "infra.json", "엔진 응답 JSON 역직렬화 경계 — 숫자를 비교·정렬·분류하지 않는다"),
            new Allowed(P + "audit.tsa.stub", "BC 인증서·토큰 생성 API의 ASN.1 INTEGER 일련번호(BigInteger 매개변수) — 금액·비율이 아니며 "
                    + "연산·비교하지 않는다(Phase 5 계획 §3). 검증 쪽(audit.tsa)은 ASN1Integer·hex로 다뤄 BigInteger를 쓰지 않는다"));

    /** {@code org.bouncycastle..} 참조 허용 패키지(4 수용심사 승인 ① — 테스트 픽스처는 이 검사 대상 밖). 하위 패키지 불포함. */
    static final List<Allowed> BOUNCY_CASTLE_PACKAGES = List.of(
            new Allowed(P + "audit.tsa", "RFC 3161 요청 생성·응답 수락·토큰 검증(TimestampClient·TimestampVerifier)"),
            new Allowed(P + "audit.tsa.stub", "로컬 스텁 TSA — BC TimeStampResponseGenerator·자체 서명 인증서(데모 프로파일이 런타임에 쓴다)"));

    /** RatioLabel.value()(엔진 ratioToAvg 원문 문자열) 호출 허용 패키지 — 전부 "원문을 그대로 옮기는" 자리다. */
    static final List<Allowed> RATIO_LABEL_VALUE_PACKAGES = List.of(
            new Allowed(P + "seal.renderer", "증거 패키지·준법 화면용 렌더링에 원문을 인쇄(확인서 본문에는 비율을 인쇄하지 않는다)"),
            new Allowed(P + "api.mapper", "준법 API DTO에 원문 문자열을 그대로 싣는다"),
            new Allowed(P + "seal.canonical", "봉인 정규화 JSON(JCS)에 원문을 바이트 그대로 싣는다 — 해시 재현의 전제(Phase 0 심사 3절 8번)"),
            new Allowed(P + "infra.persistence", "disclosure_item.ratio_to_avg TEXT 컬럼에 원문 저장(Phase 0 심사 3절 8번)"),
            new Allowed(P + "infra.json", "엔진 응답 JSON 매핑에서 원문 문자열 보존(Phase 0 심사 3절 8번)"));

    /** GradeSnapshotItem·RatioLabel을 다루면서 순서 API를 쓸 수 있는 유일한 클래스(정수 rankInSet·gradeOrdinal만). */
    static final List<Allowed> ORDERING_CLASSES = List.of(
            new Allowed(GradeConsistencyCheck.class.getName(), "엔진 스냅샷 정합성 검증(설계서 §6.3 (i)~(v)) — 정렬 기준은 엔진이 준 정수뿐"));

    /** {@code Sensitive.reveal}(개인정보 원문 접근)을 호출할 수 있는 패키지(정확한 패키지, Phase 2 P5). */
    static final List<Allowed> PII_REVEAL_PACKAGES = List.of(
            new Allowed(P + "domain.pii", "BirthDate.matches — 본인확인 대조(상수 시간 비교, 결과만 반환)"),
            new Allowed(P + "infra.crypto", "CustomerFieldCipher — 컬럼 암호화 직전 정규 원문을 바이트로(암호화 후 0으로 지운다)"),
            new Allowed(P + "rules.pii", "MaskedView — 룰 데이터 masking으로 부분 마스킹한 문자열만 내보낸다"),
            new Allowed(P + "seal.canonical", "CanonicalDocumentBuilder — 봉인 유스케이스가 복호화한 성명을 봉인 본문에 싣는다(3A 수용심사 §3-3, "
                    + "PDF에 인쇄되는 유일한 개인정보)"));

    /** javax.crypto를 쓸 수 있는 유일한 패키지. */
    static final Allowed CRYPTO_PACKAGE = new Allowed(P + "infra.crypto", "고객 개인정보 컬럼 암호화·KEK 감싸기(설계서 §9)");

    private static JavaClasses classes;

    /** 테스트 소스셋(test·integrationTest·archTest·testFixtures) 산출물을 제외한다. */
    static final ImportOption PRODUCTION_ONLY = (Location location) ->
            new ImportOption.DoNotIncludeTests().includes(location)
                    && !location.contains("/archTest/")
                    && !location.contains("/integrationTest/")
                    && !location.contains("/testFixtures/")
                    && !location.contains("-test-fixtures");

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter().withImportOption(PRODUCTION_ONLY).importPackages("com.ga");
    }

    @Test
    void importsEveryModule() {
        // 규칙이 빈 집합에 대해 통과하는 일이 없도록, 대표 클래스가 실제로 들어왔는지 확인한다.
        assertThat(classes.contain(TenantScopedRepository.class)).isTrue();
        assertThat(classes.contain(GradeSnapshotItem.class)).isTrue();
        assertThat(classes.contain(GradeConsistencyCheck.class)).isTrue();
        assertThat(classes.containPackage("com.ga.platform.canonical")).isTrue();
        assertThat(classes.containPackage(P + "infra.persistence")).isTrue();
        assertThat(classes.containPackage(P + "app")).isTrue();
        assertThat(classes.containPackage(P + "audit.tsa.stub")).isTrue();
        assertThat(classes.containPackage(P + "audit.verify")).isTrue();
        assertThat(classes.stream().map(c -> c.getName()))
                .noneMatch(n -> n.contains(".architecture.") || n.endsWith("IT") || n.endsWith("Test"));
    }

    // (a) 모듈 의존 방향: app → api → workflow/compliance → rules/seal/sign/audit → domain → platform-core
    //     platform-spring은 infra·api·app만, platform-canonical은 rules·seal·audit·compliance(와 상위)만, infra는 app만 접근한다.
    //     infra는 포트-어댑터 방향으로 workflow·rules·sign·audit·compliance의 포트를 구현한다(설계서 §3.3).
    @Test
    void layeredModuleDependencies() {
        List<String> belowDomain = List.of("Rules", "Seal", "Sign", "Audit", "Workflow", "Compliance", "Api", "Infra", "App", "Demo");
        ArchRules.layeredDependencies(
                new Layer("PlatformCore", List.of("com.ga.platform.core.."), java.util.Set.of(
                        "PlatformSpring", "Domain", "Rules", "Seal", "Sign", "Audit", "Workflow", "Compliance", "Api", "Infra", "App", "Demo")),
                Layer.of("PlatformSpring", "com.ga.platform.spring..", "Infra", "Api", "App"),
                // Phase 1: rules·seal·audit·compliance(와 그 위 레이어)만 JCS·해시를 쓴다. domain·sign은 쓰지 않는다.
                Layer.of("PlatformCanonical", "com.ga.platform.canonical..",
                        "Rules", "Seal", "Audit", "Compliance", "Workflow", "Api", "Infra", "App", "Demo"),
                new Layer("Domain", List.of(P + "domain.."), java.util.Set.copyOf(belowDomain)),
                // 3B: 렌더러와 R-FIELD-REQUIRED가 같은 서식 결속(rules.template)을 쓴다 — Seal → Rules(3B 계획 §1)
                Layer.of("Rules", P + "rules..", "Seal", "Workflow", "Compliance", "Api", "Infra", "App", "Demo"),
                Layer.of("Seal", P + "seal..", "Workflow", "Compliance", "Api", "Infra", "App", "Demo"),
                Layer.of("Sign", P + "sign..", "Workflow", "Compliance", "Api", "Infra", "App", "Demo"),
                Layer.of("Audit", P + "audit..", "Workflow", "Compliance", "Api", "Infra", "App", "Demo"),
                Layer.of("Workflow", P + "workflow..", "Api", "Infra", "App"),
                Layer.of("Compliance", P + "compliance..", "Api", "Infra", "App"),
                Layer.of("Api", P + "api..", "App"),
                Layer.of("Infra", P + "infra..", "App"),
                Layer.of("Demo", P + "demo..", "App"),
                Layer.of("App", P + "app.."))
                .check(classes);
    }

    // (b) Spring·DB 무의존 모듈(seal 렌더러 하위 패키지 제외)
    @Test
    void springAndDbFreeModules() {
        ArchRules.springFreeModules(
                        List.of("com.ga.platform.core..", P + "domain..", P + "rules..", P + "seal.."),
                        List.of(P + "seal.renderer.."))
                .check(classes);
    }

    // (c) 전 모듈 double/float/Double/Float 금지
    @Test
    void noDoubleOrFloat() {
        ArchRules.noDoubleOrFloat().check(classes);
    }

    // (d) DB 접근은 TenantScopedRepository 상속 클래스에서만, 원시 진입점은 기반 클래스·세션 바인더에서만
    @Test
    void dbAccessOnlyViaTenantScopedRepository() {
        ArchRules.dbAccessOnlyVia(REPOSITORY_BASE.fqn(), DB_INFRASTRUCTURE).check(classes);
    }

    // (e)① BigDecimal·BigInteger는 disclosure-infra의 ..infra.json.. 에서만
    @Test
    void bigDecimalOnlyInInfraJson() {
        ArchRules.typesOnlyUsedIn(List.of(BigDecimal.class, BigInteger.class), BIG_NUMBER_PACKAGES).check(classes);
    }

    // (e)② RatioLabel.value()는 원문을 그대로 옮기는 허용 패키지(FQN)에서만
    @Test
    void ratioLabelValueOnlyInAllowlistedPackages() {
        ArchRules.methodOnlyInvokedFrom(RatioLabel.class, "value", RATIO_LABEL_VALUE_PACKAGES).check(classes);
    }

    // (e)③ GradeSnapshotItem·RatioLabel은 Comparable이 아니고, 이들을 다루는 정렬·최대·최소는 GradeConsistencyCheck에서만
    @Test
    void gradeSnapshotIsNeverOrderedOutsideConsistencyCheck() {
        ArchRules.notComparable(GradeSnapshotItem.class, RatioLabel.class).check(classes);
        ArchRules.noOrderingInClassesUsing(List.of(GradeSnapshotItem.class, RatioLabel.class), ORDERING_CLASSES)
                .check(classes);
    }

    // (e)④ 이름에 Grading·Ranking·CommissionRate 금지(엔진 클라이언트 DTO는 GradeSnapshot*)
    @Test
    void noGradingRankingOrCommissionRateClasses() {
        ArchRules.noClassNameContaining("Grading", "Ranking", "CommissionRate").check(classes);
    }

    // (f) Phase 2 P5: 개인정보 값객체의 누출 경로 — record 컴포넌트 금지, 원문 타입 필드 금지, reveal 호출처 허용 목록, javax.crypto 한정
    @Test
    void sensitiveValuesDoNotLeakThroughRecordsFieldsOrCrypto() {
        PiiRules.noRecordHoldsSensitive().check(classes);
        PiiRules.piiValuesAreHeldOnlyInsideSensitive(P + "domain.pii").check(classes);
        ArchRules.methodOnlyInvokedFrom(Sensitive.class, "reveal", PII_REVEAL_PACKAGES).check(classes);
        PiiRules.cryptoOnlyIn(CRYPTO_PACKAGE.fqn()).check(classes);
    }

    // (g) Phase 2 P6: 생년월일 대조는 상수 시간 비교
    @Test
    void birthDateMatchUsesConstantTimeComparison() {
        PiiRules.birthDateMatchIsConstantTime().check(classes);
    }

    // (h) 3B: 봉인 본문·렌더러는 벽시계·난수·환경(기본 로케일·시간대)을 읽지 않는다 — 같은 입력이면 어느 JVM에서든 같은 바이트(S1·S2)
    @Test
    void sealBodyAndRendererAreEnvironmentFree() {
        SealRules.noEnvironmentAccess(List.of(P + "seal.canonical..", P + "seal.renderer..", P + "seal.evidence..")).check(classes);
    }

    // (i) 3B: 3A D7의 스칼라 [x] 감싸기(CanonicalValue)는 봉인 경로에 나타나지 않는다 — 봉인 본문은 최상위가 객체다(수용심사 §2 D7)
    @Test
    void sealPathDoesNotUseTheScalarWrappingConvention() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAnyPackage(P + "seal..", P + "workflow.seal..")
                .should().dependOnClassesThat().haveFullyQualifiedName(P + "workflow.disclosure.CanonicalValue")
                .allowEmptyShould(true)
                .because("봉인 본문은 최상위가 객체이며 [x] 감싸기 규약을 쓰지 않는다(3A 수용심사 D7)")
                .check(classes);
    }

    // (j) Phase 5: BouncyCastle은 TSA 패키지 안에서만 — 포트·결과 타입에 BC 타입이 없어 바깥은 DER 바이트·record만 본다
    @Test
    void bouncyCastleOnlyInTsaPackages() {
        String[] allowed = BOUNCY_CASTLE_PACKAGES.stream().map(Allowed::fqn).toArray(String[]::new);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackages(allowed)
                .should().dependOnClassesThat().resideInAPackage("org.bouncycastle..")
                .allowEmptyShould(true)
                .because("4 수용심사 승인 ①: org.bouncycastle.. 참조는 disclosure-audit의 ..tsa..와 테스트 픽스처만")
                .check(classes);
    }

    // (k) Phase 5 승인 Q1: verify package는 생산자(seal)·workflow·infra·DB·네트워크·파일 시스템·키에 의존하지 않는다 — 입력 바이트만 받아 자체
    //     리더와 계약 스키마로 검증한다(스텁·HTTP TSA 어댑터도 쓰지 않는다)
    @Test
    void verifyPackageIsIndependentOfTheProducerAndOfEveryEnvironment() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage(P + "audit.verify..")
                .should().dependOnClassesThat().resideInAnyPackage(P + "seal..", P + "workflow..", P + "infra..", P + "compliance..", P + "api..",
                        P + "app..", P + "audit.tsa.http..", P + "audit.tsa.stub..", "java.sql..", "javax.sql..", "java.net..", "java.nio.file..",
                        "javax.crypto..", "org.springframework..")
                .because("5 계획 승인 Q1: verify package는 DB·키·워크플로·생산자 코드 의존 0 — 패키지와 영수증·신뢰 앵커 바이트만으로 검증한다")
                .check(classes);
    }

    // 허용 목록의 폐기 항목 0: 목록의 모든 FQN이 실제로 존재한다(Phase 0 심사 R1)
    @Test
    void allowlistsHaveNoStaleEntries() {
        List<Allowed> classAllowlist = Stream.of(List.of(REPOSITORY_BASE), DB_INFRASTRUCTURE, ORDERING_CLASSES)
                .flatMap(List::stream).toList();
        List<Allowed> packageAllowlist = Stream.of(BIG_NUMBER_PACKAGES, RATIO_LABEL_VALUE_PACKAGES, PII_REVEAL_PACKAGES, List.of(CRYPTO_PACKAGE),
                        BOUNCY_CASTLE_PACKAGES)
                .flatMap(List::stream).toList();
        assertThat(ArchRules.staleClasses(classes, classAllowlist)).as("stale class entries").isEmpty();
        assertThat(ArchRules.stalePackages(classes, packageAllowlist)).as("stale package entries").isEmpty();
    }
}
