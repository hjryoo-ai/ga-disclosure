package com.ga.disclosure.architecture;

import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.grade.RatioLabel;
import com.ga.disclosure.rules.grade.GradeConsistencyCheck;
import com.ga.platform.core.arch.ArchRules;
import com.ga.platform.core.arch.ArchRules.Layer;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import com.ga.platform.spring.jdbc.TenantSessionBinder;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C5: 모듈 의존·Spring 무의존·double 금지·저장소 상속·수수료율 연산 금지 4종. 규칙 정의는 platform-core {@link ArchRules}
 * (포털과 공유)이고, 이 클래스는 ga-disclosure의 패키지·허용 목록으로 규칙을 구성할 뿐이다.
 * 검사 대상은 전 모듈의 운영 클래스({@code com.ga..})이며 테스트·테스트 픽스처·이 소스셋은 제외한다.
 */
class ArchitectureRulesTest {

    static final String P = "com.ga.disclosure.";

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
        assertThat(classes.containPackage(P + "infra.persistence")).isTrue();
        assertThat(classes.containPackage(P + "app")).isTrue();
        assertThat(classes.stream().map(c -> c.getName()))
                .noneMatch(n -> n.contains(".architecture.") || n.endsWith("IT") || n.endsWith("Test"));
    }

    // (a) 모듈 의존 방향: app → api → workflow/compliance → rules/seal/sign/audit → domain → platform-core
    //     platform-spring은 infra·api·app만, infra는 app만 접근한다.
    @Test
    void layeredModuleDependencies() {
        List<String> belowDomain = List.of("Rules", "Seal", "Sign", "Audit", "Workflow", "Compliance", "Api", "Infra", "App", "Demo");
        ArchRules.layeredDependencies(
                new Layer("PlatformCore", List.of("com.ga.platform.core.."), java.util.Set.of(
                        "PlatformSpring", "Domain", "Rules", "Seal", "Sign", "Audit", "Workflow", "Compliance", "Api", "Infra", "App", "Demo")),
                Layer.of("PlatformSpring", "com.ga.platform.spring..", "Infra", "Api", "App"),
                new Layer("Domain", List.of(P + "domain.."), java.util.Set.copyOf(belowDomain)),
                Layer.of("Rules", P + "rules..", "Workflow", "Compliance", "Api", "Infra", "App", "Demo"),
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
        ArchRules.dbAccessOnlyVia(TenantScopedRepository.class, TenantSessionBinder.class).check(classes);
    }

    // (e)① BigDecimal·BigInteger는 disclosure-infra의 ..infra.json.. 에서만
    @Test
    void bigDecimalOnlyInInfraJson() {
        ArchRules.typesOnlyUsedIn(List.of(BigDecimal.class, BigInteger.class), P + "infra.json..").check(classes);
    }

    // (e)② RatioLabel.value()는 봉인 렌더러와 API DTO 매퍼에서만
    @Test
    void ratioLabelValueOnlyInRendererAndDtoMapper() {
        ArchRules.methodOnlyInvokedFrom(RatioLabel.class, "value", P + "seal.renderer..", P + "api.mapper..").check(classes);
    }

    // (e)③ GradeSnapshotItem·RatioLabel은 Comparable이 아니고, 이들을 다루는 정렬·최대·최소는 GradeConsistencyCheck에서만
    @Test
    void gradeSnapshotIsNeverOrderedOutsideConsistencyCheck() {
        ArchRules.notComparable(GradeSnapshotItem.class, RatioLabel.class).check(classes);
        ArchRules.noOrderingInClassesUsing(List.of(GradeSnapshotItem.class, RatioLabel.class), GradeConsistencyCheck.class)
                .check(classes);
    }

    // (e)④ 이름에 Grading·Ranking·CommissionRate 금지(엔진 클라이언트 DTO는 GradeSnapshot*)
    @Test
    void noGradingRankingOrCommissionRateClasses() {
        ArchRules.noClassNameContaining("Grading", "Ranking", "CommissionRate").check(classes);
    }
}
