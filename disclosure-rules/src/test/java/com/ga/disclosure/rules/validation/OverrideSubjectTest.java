package com.ga.disclosure.rules.validation;

import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.testing.Snapshots;
import com.ga.disclosure.rules.testing.TestItem;
import com.ga.disclosure.rules.testing.TestSubject;
import com.ga.disclosure.rules.validation.standard.StandardValidations;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3A 계획 Q3: 오버라이드 가능한 실패는 대상(정규 JSON)을 싣고, 승인은 그 해시에 귀속된다. 대상은 항목 순서와 무관한 집합이며
 * 대상이 바뀌면 해시가 바뀐다. 임시등록 항목은 스냅샷이 적용되면 로컬 산출불가(TEMP_PRODUCT, LOCAL)로 대상에 들어간다.
 */
class OverrideSubjectTest {

    private static final LocalDate CONSULT = LocalDate.of(2026, 9, 23);
    private static final EffectiveRule RULE = RuleResolver.merge(CONSULT,
            Bundles.global(Bundles.rule(Bundles.DISC_2026_07), RuleStatus.ACTIVE, null), null);
    private static final TemplateResolution TEMPLATE =
            TemplateResolver.resolution(Bundles.template(Bundles.template(Bundles.STANDARD_V1), null));
    private static final ValidationRegistry REGISTRY = StandardValidations.registry();

    private static ValidationResult run(String ruleId, TestSubject subject) {
        return REGISTRY.run(ValidationStage.SEAL, subject, RULE, TEMPLATE).stream().filter(r -> r.ruleId().equals(ruleId))
                .findFirst().orElseThrow();
    }

    private static TestSubject subject(List<TestItem> items) {
        return TestSubject.of("PG-HEALTH", CONSULT, items);
    }

    private static final TestItem A = TestItem.of("INS-A", "PRD-1", "PG-HEALTH");
    private static final TestItem TEMP_B = TestItem.of("INS-B", "X", "PG-HEALTH").temp("Q-2026-0001");
    private static final TestItem TEMP_C = TestItem.of("INS-C", "X", "PG-HEALTH").temp("Q-2026-0002");

    @Test
    void overridableFailureCarriesItsSubjectAndPassesOrBlockingFailuresDoNot() {
        ValidationResult temp = run("R-TEMP-PRODUCT", subject(List.of(A, TEMP_B)));
        assertThat(temp.overridable()).isTrue();
        assertThat(temp.subjectHash()).get().asString().matches("[0-9a-f]{64}");
        assertThat(temp.subject().toString()).contains("Q-2026-0001").contains("INS-B");
        assertThat(run("R-TEMP-PRODUCT", subject(List.of(A))).subjectHash()).isEmpty();
        assertThat(run("R-MIN-COMPARE", subject(List.of(A))).subjectHash()).isEmpty();

        assertThatThrownBy(() -> new ValidationResult("R-X", false, "m", true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ValidationResult("R-X", false, "m", false, temp.subject())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void subjectIsASetIndependentOfItemOrder() {
        String forward = run("R-TEMP-PRODUCT", subject(List.of(A, TEMP_B, TEMP_C))).subjectHash().orElseThrow();
        String backward = run("R-TEMP-PRODUCT", subject(List.of(TEMP_C, A, TEMP_B))).subjectHash().orElseThrow();
        assertThat(backward).isEqualTo(forward);
    }

    @Test
    void anotherTempProductChangesTheSubject() {
        String approved = run("R-TEMP-PRODUCT", subject(List.of(A, TEMP_B))).subjectHash().orElseThrow();
        String swapped = run("R-TEMP-PRODUCT", subject(List.of(A, TEMP_C))).subjectHash().orElseThrow();
        String requoted = run("R-TEMP-PRODUCT", subject(List.of(A, TestItem.of("INS-B", "X", "PG-HEALTH").temp("Q-2026-0009"))))
                .subjectHash().orElseThrow();
        assertThat(swapped).isNotEqualTo(approved);
        assertThat(requoted).isNotEqualTo(approved);
    }

    @Test
    void gradeUnavailableCountsEngineAndLocalTempItemsOnceGraded() {
        List<TestItem> items = List.of(A, TestItem.of("INS-D", "PRD-4", "PG-HEALTH"), TEMP_B);
        TestSubject ungraded = subject(items);
        assertThat(run("R-GRADE-UNAVAILABLE", ungraded).passed()).as("산출 전에는 판정하지 않는다").isTrue();

        TestSubject graded = ungraded.withSnapshot(Snapshots.snapshot(TieBreak.SHARED_RANK,
                Snapshots.ok("INS-A:PRD-1", 2, 1, false), Snapshots.unavailable("INS-D:PRD-4", "NO_RATE_DATA")));
        ValidationResult r = run("R-GRADE-UNAVAILABLE", graded);
        assertThat(r.overridable()).isTrue();
        assertThat(r.subject().toString()).contains("NO_RATE_DATA", "ENGINE", "TEMP_PRODUCT", "LOCAL", "Q-2026-0001");

        // 같은 산출불가 집합을 다른 스냅샷(재산출)으로 받아도 대상은 같다
        TestSubject regraded = ungraded.withSnapshot(Snapshots.snapshot(TieBreak.STRICT,
                Snapshots.unavailable("INS-D:PRD-4", "NO_RATE_DATA"), Snapshots.ok("INS-A:PRD-1", 3, 1, false)));
        assertThat(run("R-GRADE-UNAVAILABLE", regraded).subjectHash()).isEqualTo(r.subjectHash());

        // 사유가 바뀌면 대상이 바뀐다
        TestSubject otherReason = ungraded.withSnapshot(Snapshots.snapshot(TieBreak.SHARED_RANK,
                Snapshots.ok("INS-A:PRD-1", 2, 1, false), Snapshots.unavailable("INS-D:PRD-4", "NOT_IN_GROUP")));
        assertThat(run("R-GRADE-UNAVAILABLE", otherReason).subjectHash()).isNotEqualTo(r.subjectHash());
    }

    @Test
    void tempItemsAreNotRequestedFromTheEngine() {
        TestSubject graded = subject(List.of(A, TestItem.of("INS-D", "PRD-4", "PG-HEALTH"), TEMP_B))
                .withSnapshot(Snapshots.snapshot(TieBreak.SHARED_RANK,
                        Snapshots.ok("INS-A:PRD-1", 2, 1, false), Snapshots.ok("INS-D:PRD-4", 3, 2, false)));
        assertThat(run("R-GRADE-REQUIRED", graded).passed()).as("임시등록 항목에는 엔진 결과가 필요 없다").isTrue();
        assertThat(run("R-RANK-MONOTONIC", graded).passed()).as("집합 검사는 요청한 항목에만").isTrue();
    }
}
