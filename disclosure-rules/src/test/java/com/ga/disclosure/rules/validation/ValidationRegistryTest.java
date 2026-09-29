package com.ga.disclosure.rules.validation;

import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.ResolutionFailure;
import com.ga.disclosure.rules.resolve.RuleResolutionException;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.FieldScope;
import com.ga.disclosure.rules.template.TemplateField;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.testing.Snapshots;
import com.ga.disclosure.rules.testing.TestItem;
import com.ga.disclosure.rules.testing.TestSubject;
import com.ga.disclosure.rules.validation.standard.StandardValidations;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static com.ga.disclosure.rules.testing.Snapshots.ok;
import static com.ga.disclosure.rules.testing.Snapshots.unavailable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Phase 1 C8: 설계서 §6.2 검증 규칙 12종 각각 통과·실패 표본, 목록에 없는 ID → UNKNOWN_VALIDATION(아무것도 실행하지 않음),
 * 레지스트리에만 있는 규칙은 실행되지 않음, 실행 순서 = 룰 데이터 순서.
 * 기준 표본(모든 규칙 통과)은 DISC-2026-07 번들 룰 + STANDARD-v1 번들 서식 + 3사 비교·추천 2건·서명 3건이다.
 */
class ValidationRegistryTest {

    private static final LocalDate CONSULT = LocalDate.parse("2026-09-23");
    private static final Instant SEALED = Instant.parse("2026-09-23T01:00:00Z");
    private static final ValidationRegistry REGISTRY = StandardValidations.registry();
    private static final EffectiveRule RULE = RuleResolver.merge(CONSULT,
            Bundles.global(Bundles.rule(Bundles.DISC_2026_07), RuleStatus.ACTIVE, null), null);
    private static final TemplateResolution TEMPLATE =
            TemplateResolver.resolution(Bundles.template(Bundles.template(Bundles.STANDARD_V1), null));

    /** 서식의 PER_ITEM 필수 항목 전부에 값을 채운 항목. 코드 이름은 서식 데이터에서 읽는다. */
    private static TestItem filled(TestItem item) {
        Map<String, String> values = new HashMap<>();
        TEMPLATE.requiredFields().stream().filter(f -> f.scope() == FieldScope.PER_ITEM).forEach(f -> values.put(f.code(), "값"));
        return item.fields(values);
    }

    private static TestSubject baseline() {
        List<TestItem> items = List.of(
                filled(TestItem.of("INS-A", "PRD-1", "PG-HEALTH").recommended("PREMIUM")),
                filled(TestItem.of("INS-B", "PRD-2", "PG-HEALTH").recommended("COVERAGE")),
                filled(TestItem.of("INS-C", "PRD-3", "PG-HEALTH")));
        TestSubject subject = TestSubject.of("PG-HEALTH", CONSULT, items)
                .withSnapshot(Snapshots.snapshot(TieBreak.SHARED_RANK,
                        ok("INS-C:PRD-3", 5, 3, false), ok("INS-A:PRD-1", 2, 1, false), ok("INS-B:PRD-2", 3, 2, false)))
                .withDeadline(SEALED.plusSeconds(7 * 86_400));
        for (TemplateField f : TEMPLATE.requiredFields()) {
            if (f.scope() == FieldScope.PER_DOCUMENT) {
                subject = subject.withDocumentField(f.code(), "값");
            }
        }
        for (int i = 0; i < RULE.signerSet().size(); i++) {
            subject = subject.signedBy(RULE.signerSet().get(i), SEALED.plusSeconds(60L * (i + 1)));
        }
        return subject;
    }

    private static ValidationResult result(String ruleId, TestSubject subject) {
        return result(ruleId, subject, RULE);
    }

    private static ValidationResult result(String ruleId, TestSubject subject, EffectiveRule rule) {
        return REGISTRY.run(subject, rule, TEMPLATE).stream().filter(r -> r.ruleId().equals(ruleId)).findFirst().orElseThrow();
    }

    private static TestSubject mapItems(TestSubject s, UnaryOperator<List<ValidationSubject.Item>> f) {
        return s.withItems(f.apply(new ArrayList<>(s.items())));
    }

    private static List<ValidationSubject.Item> replace(List<ValidationSubject.Item> items, int index, ValidationSubject.Item item) {
        items.set(index, item);
        return items;
    }

    // ------------------------------------------------------------------ 기준 표본

    @Test
    void baselinePassesEveryRuleInDataOrder() {
        List<ValidationResult> results = REGISTRY.run(baseline(), RULE, TEMPLATE);
        assertThat(results).extracting(ValidationResult::ruleId).containsExactlyElementsOf(RULE.validations());
        assertThat(results).hasSize(12).allSatisfy(r -> assertThat(r.passed()).as(r.ruleId() + ": " + r.message()).isTrue());
    }

    // ------------------------------------------------------------------ 규칙별 실패 표본

    @Test
    void minCompare() {
        TestSubject two = mapItems(baseline(), items -> items.subList(0, 2));
        assertThat(result("R-MIN-COMPARE", two).passed()).isFalse();
        assertThat(result("R-MIN-COMPARE", two).overridable()).isFalse();
    }

    @Test
    void distinctInsurer() {
        TestSubject same = mapItems(baseline(), items -> replace(items, 2, filled(TestItem.of("INS-A", "PRD-9", "PG-HEALTH"))));
        assertThat(result("R-DISTINCT-INSURER", same).passed()).isFalse();
        ObjectNode relaxed = (ObjectNode) RULE.body();
        relaxed.put("distinctInsurer", false);
        assertThat(result("R-DISTINCT-INSURER", same, EffectiveRule.of(CONSULT, RULE.globalRuleVersionId(), null, relaxed)).passed())
                .isTrue();
    }

    @Test
    void sameGroup() {
        TestSubject other = mapItems(baseline(), items -> replace(items, 2, ((TestItem) items.get(2)).group("PG-OTHER")));
        assertThat(result("R-SAME-GROUP", other).passed()).isFalse();
    }

    @Test
    void panel() {
        TestSubject offPanel = baseline().withPanel(Set.of(InsurerCode.of("INS-A"), InsurerCode.of("INS-B")));
        ValidationResult r = result("R-PANEL", offPanel);
        assertThat(r.passed()).isFalse();
        assertThat(r.message()).contains("INS-C");
    }

    @Test
    void gradeRequired() {
        assertThat(result("R-GRADE-REQUIRED", baseline().withSnapshot(null)).passed()).isFalse();
        TestSubject partial = baseline().withSnapshot(Snapshots.snapshot(TieBreak.SHARED_RANK,
                ok("INS-A:PRD-1", 2, 1, false), ok("INS-B:PRD-2", 3, 2, false)));
        assertThat(result("R-GRADE-REQUIRED", partial).passed()).isFalse();
        assertThat(result("R-GRADE-REQUIRED", baseline().withSnapshot(null).withLargeGa(false)).passed()).as("대형 GA 아님").isTrue();
    }

    @Test
    void gradeUnavailableIsOverridable() {
        TestSubject s = baseline().withSnapshot(Snapshots.snapshot(TieBreak.SHARED_RANK,
                ok("INS-A:PRD-1", 2, 1, false), ok("INS-B:PRD-2", 3, 2, false), unavailable("INS-C:PRD-3", "NO_RATE_DATA")));
        ValidationResult r = result("R-GRADE-UNAVAILABLE", s);
        assertThat(r.passed()).isFalse();
        assertThat(r.overridable()).isTrue();
        assertThat(r.message()).contains("NO_RATE_DATA");
        assertThat(result("R-GRADE-REQUIRED", s).passed()).as("UNAVAILABLE도 결과가 있는 것").isTrue();
        assertThat(result("R-RANK-MONOTONIC", s).passed()).as("순위는 OK 항목만으로").isTrue();
    }

    @Test
    void rankMonotonic() {
        TestSubject inverted = baseline().withSnapshot(Snapshots.snapshot(TieBreak.SHARED_RANK,
                ok("INS-A:PRD-1", 5, 1, false), ok("INS-B:PRD-2", 3, 2, false), ok("INS-C:PRD-3", 2, 3, false)));
        ValidationResult r = result("R-RANK-MONOTONIC", inverted);
        assertThat(r.passed()).isFalse();
        assertThat(r.overridable()).isFalse();
    }

    @Test
    void reason() {
        TestSubject unknown = mapItems(baseline(), items -> replace(items, 0, ((TestItem) items.get(0)).reasons("NOT_A_CODE")));
        assertThat(result("R-REASON", unknown).passed()).isFalse();
        TestSubject onlyAuto = mapItems(baseline(), items -> replace(items, 0, ((TestItem) items.get(0)).reasons("CUSTOMER_REQUEST")));
        assertThat(result("R-REASON", onlyAuto).message()).contains("설계사가 고른 사유 코드가 없다");
        TestSubject otherNoText = mapItems(baseline(), items -> replace(items, 0, ((TestItem) items.get(0)).reasons("OTHER")));
        assertThat(result("R-REASON", otherNoText).passed()).isFalse();
        TestSubject otherWithText = mapItems(baseline(), items -> replace(items, 0, ((TestItem) items.get(0)).reasons("OTHER").text("재정 상황")));
        assertThat(result("R-REASON", otherWithText).passed()).isTrue();
        String tooLong = "가".repeat(RULE.reasonTextMaxLength() + 1);
        TestSubject longText = mapItems(baseline(), items -> replace(items, 0, ((TestItem) items.get(0)).text(tooLong)));
        assertThat(result("R-REASON", longText).message()).contains("상한");
    }

    @Test
    void tempProduct() {
        TestSubject noQuote = mapItems(baseline(), items -> replace(items, 2, ((TestItem) items.get(2)).temp(null)));
        assertThat(result("R-TEMP-PRODUCT", noQuote).passed()).isFalse();
        assertThat(result("R-TEMP-PRODUCT", noQuote).overridable()).isFalse();
        TestSubject withQuote = mapItems(baseline(), items -> replace(items, 2, ((TestItem) items.get(2)).temp("Q-2026-0001")));
        assertThat(result("R-TEMP-PRODUCT", withQuote).passed()).isFalse();
        assertThat(result("R-TEMP-PRODUCT", withQuote).overridable()).isTrue();
    }

    @Test
    void requested() {
        TestSubject missingAuto = mapItems(baseline(), items -> replace(items, 2, ((TestItem) items.get(2)).requested()));
        assertThat(result("R-REQUESTED", missingAuto).passed()).isFalse();
        TestSubject withAuto = mapItems(baseline(), items -> replace(items, 2, ((TestItem) items.get(2)).requested().reasons("CUSTOMER_REQUEST")));
        assertThat(result("R-REQUESTED", withAuto).passed()).isTrue();
        TestSubject autoOnOthers = mapItems(baseline(), items -> replace(items, 1, ((TestItem) items.get(1)).reasons("COVERAGE", "CUSTOMER_REQUEST")));
        assertThat(result("R-REQUESTED", autoOnOthers).passed()).isFalse();
    }

    @Test
    void signerSet() {
        TestSubject base = baseline();
        List<SignerRole> required = RULE.signerSet();
        TestSubject missingLast = base.withItems(base.items());
        TestSubject partial = TestSubject.of("PG-HEALTH", CONSULT, base.items()).withSnapshot(base.gradeSnapshot().orElseThrow())
                .withDeadline(base.signDeadline().orElseThrow());
        for (int i = 0; i < required.size() - 1; i++) {
            partial = partial.signedBy(required.get(i), SEALED.plusSeconds(60L * (i + 1)));
        }
        assertThat(result("R-SIGNER-SET", partial).message()).contains("받지 못한 필수 서명");
        TestSubject reversed = TestSubject.of("PG-HEALTH", CONSULT, base.items()).withDeadline(base.signDeadline().orElseThrow());
        for (int i = required.size() - 1; i >= 0; i--) {
            reversed = reversed.signedBy(required.get(i), SEALED.plusSeconds(60L * (required.size() - i)));
        }
        assertThat(result("R-SIGNER-SET", reversed).message()).contains("순서");
        assertThat(result("R-SIGNER-SET", missingLast.withDeadline(SEALED)).message()).contains("기한");
        assertThat(result("R-SIGNER-SET", missingLast.withDeadline(null)).passed()).isFalse();
    }

    @Test
    void fieldRequired() {
        TemplateField perItem = TEMPLATE.requiredFields().stream().filter(f -> f.scope() == FieldScope.PER_ITEM).findFirst().orElseThrow();
        TestSubject missingItemValue = mapItems(baseline(), items -> replace(items, 1, ((TestItem) items.get(1)).field(perItem.code(), " ")));
        assertThat(result("R-FIELD-REQUIRED", missingItemValue).message()).contains(perItem.code() + "@INS-B:PRD-2");
        TemplateField perDocument = TEMPLATE.requiredFields().stream().filter(f -> f.scope() == FieldScope.PER_DOCUMENT).findFirst().orElseThrow();
        assertThat(result("R-FIELD-REQUIRED", baseline().withDocumentField(perDocument.code(), "")).passed()).isFalse();
    }

    // ------------------------------------------------------------------ 레지스트리 규약

    @Test
    void unknownValidationIdFailsBeforeAnythingRuns() {
        ObjectNode body = (ObjectNode) RULE.body();
        body.putArray("validations").add("R-MIN-COMPARE").add("R-NOT-REGISTERED");
        EffectiveRule rule = EffectiveRule.of(CONSULT, RULE.globalRuleVersionId(), null, body);
        List<String> executed = new ArrayList<>();
        ValidationRegistry spying = ValidationRegistry.of(List.of(new Recording("R-MIN-COMPARE", executed)));
        RuleResolutionException e = catchThrowableOfType(RuleResolutionException.class, () -> spying.run(baseline(), rule, TEMPLATE));
        assertThat(e.failure()).isEqualTo(ResolutionFailure.UNKNOWN_VALIDATION);
        assertThat(e).hasMessageContaining("R-NOT-REGISTERED");
        assertThat(executed).isEmpty();
    }

    @Test
    void registeredButUnlistedValidationsDoNotRunAndOrderFollowsData() {
        ObjectNode body = (ObjectNode) RULE.body();
        body.putArray("validations").add("R-REASON").add("R-MIN-COMPARE");
        EffectiveRule rule = EffectiveRule.of(CONSULT, RULE.globalRuleVersionId(), null, body);
        List<String> executed = new ArrayList<>();
        ValidationRegistry registry = ValidationRegistry.of(List.of(
                new Recording("R-MIN-COMPARE", executed), new Recording("R-REASON", executed), new Recording("R-DORMANT", executed)));
        assertThat(registry.run(baseline(), rule, TEMPLATE)).extracting(ValidationResult::ruleId).containsExactly("R-REASON", "R-MIN-COMPARE");
        assertThat(executed).containsExactly("R-REASON", "R-MIN-COMPARE");
    }

    @Test
    void registryHasExactlyTheTwelveDesignRules() {
        assertThat(REGISTRY.registeredIds()).containsExactlyInAnyOrderElementsOf(RULE.validations()).hasSize(12);
    }

    private record Recording(String id, List<String> executed) implements Validation {
        @Override
        public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
            executed.add(id);
            return ValidationResult.pass(id, "recorded");
        }
    }
}
