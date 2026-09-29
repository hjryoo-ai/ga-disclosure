package com.ga.platform.core.arch;

import com.ga.platform.core.arch.ArchRules.Allowed;
import com.ga.platform.core.arch.fixtures.ordering.OrderingFixtures;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 규칙 라이브러리 자체의 음성·양성 테스트. 표본(fixtures)은 의도적 위반을 영구 보존한 것이다 —
 * 규칙이 약해지면(위반을 놓치면) 이 테스트가 실패한다.
 */
class ArchRulesTest {

    private static final String FIX = "com.ga.platform.core.arch.fixtures";

    private static JavaClasses importPackage(String suffix) {
        return new ClassFileImporter().importPackages(FIX + "." + suffix);
    }

    private static String report(ArchRule rule, JavaClasses classes) {
        EvaluationResult result = rule.evaluate(classes);
        return String.join("\n", result.getFailureReport().getDetails());
    }

    @Test
    void noDoubleOrFloatCatchesFieldsParametersReturnsAndCalls() {
        String report = report(ArchRules.noDoubleOrFloat(), importPackage("floating"));
        assertThat(report)
                .contains("DoubleField.value")
                .contains("FloatParameter.accept")
                .contains("BoxedReturn.produce")
                .contains("Double.parseDouble")
                .contains("Math.random")
                .doesNotContain("Clean");
    }

    @Test
    void orderingIsForbiddenNextToElementTypesExceptAllowedClass() {
        ArchRule rule = ArchRules.noOrderingInClassesUsing(
                List.of(OrderingFixtures.Item.class, OrderingFixtures.Label.class),
                List.of(new Allowed(OrderingFixtures.AllowedChecker.class.getName(), "fixture")));
        String report = report(rule, importPackage("ordering"));
        assertThat(report)
                .contains("SortsItemsByComparator")
                .contains("MaxOfItems")
                .contains("SortsLabelStrings")
                .contains("AllowedChecker$PublicHelper")
                .doesNotContain("AllowedChecker.monotonic")
                .doesNotContain("PrivateHelper")
                .doesNotContain("UnrelatedSorter");
    }

    @Test
    void notComparableCatchesComparableImplementation() {
        assertThat(ArchRules.notComparable(OrderingFixtures.Label.class, OrderingFixtures.Item.class)
                .evaluate(importPackage("ordering")).hasViolation()).isFalse();
        assertThat(ArchRules.notComparable(OrderingFixtures.ComparableLabel.class)
                .evaluate(importPackage("ordering")).hasViolation()).isTrue();
    }

    @Test
    void methodOnlyInvokedFromCatchesCallsAndMethodReferences() {
        ArchRule rule = ArchRules.methodOnlyInvokedFrom(OrderingFixtures.Label.class, "value",
                List.of(new Allowed(FIX + ".ordering.allowed", "fixture")));
        String report = report(rule, importPackage("ordering"));
        assertThat(report)
                .contains("SortsLabelStrings")
                .contains("ReadsLabelByReference")
                .as("패키지 허용은 하위 패키지를 포함하지 않는다").contains("NestedPackageReader")
                .doesNotContain("LabelRenderer");
    }

    @Test
    void dbAccessOnlyViaRepositoryBase() {
        ArchRule rule = ArchRules.dbAccessOnlyVia(FIX + ".db.BaseRepository",
                List.of(new Allowed(FIX + ".db.Gateway", "fixture"), new Allowed(FIX + ".db.Binder", "fixture")));
        String report = report(rule, importPackage("db"));
        assertThat(report)
                .contains("RogueDao")
                .contains("SneakyRepository")
                .as("허용 클래스의 public 중첩 클래스는 위반").contains("Binder$LeakyNested")
                .doesNotContain("GoodRepository")
                .doesNotContain("HiddenHelper")
                .doesNotContain("Class <" + FIX + ".db.Binder>")
                .doesNotContain("Gateway.<init>(javax.sql.DataSource)")
                .doesNotContain("Gateway.dataSource");
    }

    @Test
    void layeredDependenciesCatchUpwardAccess() {
        ArchRule rule = ArchRules.layeredDependencies(
                ArchRules.Layer.of("High", FIX + ".layers.high.."),
                ArchRules.Layer.of("Low", FIX + ".layers.low..", "High"),
                ArchRules.Layer.of("Empty", FIX + ".layers.empty..", "High"));
        String report = report(rule, importPackage("layers"));
        assertThat(report).contains("UpwardAccess").doesNotContain("Method <" + FIX + ".layers.high.High.value");
    }

    @Test
    void typesOnlyUsedInAllowsOnlyListedPackages() {
        String report = report(ArchRules.typesOnlyUsedIn(List.of(BigDecimal.class, BigInteger.class),
                        List.of(new Allowed(FIX + ".misc.json", "fixture"))),
                importPackage("misc"));
        assertThat(report).contains("UsesBigDecimal").doesNotContain("JsonMapper");
    }

    @Test
    void noClassNameContainingForbiddenFragments() {
        String report = report(ArchRules.noClassNameContaining("Grading", "Ranking", "CommissionRate"), importPackage("misc"));
        assertThat(report).contains("CommissionRateCalculator").contains("RankingHelper").doesNotContain("UsesBigDecimal");
    }

    @Test
    void allowlistEntriesMustBeExactFqnsWithReasons() {
        assertThatThrownBy(() -> new Allowed(FIX + ".db..", "pattern")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Allowed(FIX + ".db.*", "pattern")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Allowed(FIX + ".db.Binder", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staleAllowlistEntriesAreReported() {
        JavaClasses db = importPackage("db");
        assertThat(ArchRules.staleClasses(db, List.of(
                new Allowed(FIX + ".db.Binder", "exists"),
                new Allowed(FIX + ".db.Binder$HiddenHelper", "exists (nested)"),
                new Allowed(FIX + ".db.RemovedGateway", "stale"))))
                .containsExactly(FIX + ".db.RemovedGateway");

        JavaClasses ordering = importPackage("ordering");
        assertThat(ArchRules.stalePackages(ordering, List.of(
                new Allowed(FIX + ".ordering.allowed", "exists"),
                new Allowed(FIX + ".ordering", "exists"),
                new Allowed(FIX + ".ordering.renamed", "stale"))))
                .containsExactly(FIX + ".ordering.renamed");
    }

    @Test
    void springFreeModulesCatchJdbcUnlessExcluded() {
        JavaClasses misc = importPackage("misc");
        assertThat(report(ArchRules.springFreeModules(List.of(FIX + ".misc.."), List.of()), misc)).contains("UsesJdbc");
        assertThat(ArchRules.springFreeModules(List.of(FIX + ".misc.."), List.of(FIX + ".misc"))
                .evaluate(misc).hasViolation()).isFalse();
    }
}
