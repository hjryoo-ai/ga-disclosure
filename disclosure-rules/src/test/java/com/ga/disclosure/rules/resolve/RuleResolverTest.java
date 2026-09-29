package com.ga.disclosure.rules.resolve;

import com.ga.disclosure.domain.enums.ManagerConfirmMode;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.rules.bundle.RuleBundle;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.testing.InMemoryRuleVersionPort;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 C2·C3: scope별 단건 해석(GLOBAL 0건·2건, TENANT 2건 실패 — 인메모리 포트로 DB 제약 없이 2건 주입)과
 * tenantOverridable 병합(밖 키 거부, 안 키 병합, 중첩 객체 통째 교체, TENANT 없음 → GLOBAL 그대로).
 */
class RuleResolverTest {

    private static final TenantId T = TenantId.of("T1");
    private static final LocalDate D = LocalDate.parse("2026-09-23");
    private static final LocalDate FROM = LocalDate.parse("2026-07-01");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RuleBundle Y2026 = Bundles.rule(Bundles.DISC_2026_07);

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static InMemoryRuleVersionPort withGlobal() {
        return new InMemoryRuleVersionPort().add(Bundles.global(Y2026, RuleStatus.ACTIVE, null));
    }

    private static RuleResolutionException failure(InMemoryRuleVersionPort port) {
        return (RuleResolutionException) org.assertj.core.api.Assertions.catchThrowable(() -> new RuleResolver(port).resolve(T, D));
    }

    // ------------------------------------------------------------------ C2

    @Test
    void noGlobalRuleFails() {
        InMemoryRuleVersionPort port = new InMemoryRuleVersionPort()
                .add(Bundles.tenant("HOUSE", FROM, null, RuleStatus.ACTIVE, json("{}")))
                .add(Bundles.global(Y2026, RuleStatus.APPROVED, null));          // 활성화 전은 대상이 아니다
        assertThat(failure(port).failure()).isEqualTo(ResolutionFailure.NO_GLOBAL_RULE);
    }

    @Test
    void twoGlobalRulesAreAmbiguous() {
        InMemoryRuleVersionPort port = withGlobal()
                .add(Bundles.global("DISC-OVERLAP", LocalDate.parse("2026-09-01"), null, RuleStatus.ACTIVE, Y2026.body()));
        RuleResolutionException e = failure(port);
        assertThat(e.failure()).isEqualTo(ResolutionFailure.AMBIGUOUS);
        assertThat(e).hasMessageContaining("DISC-2026-07").hasMessageContaining("DISC-OVERLAP");
    }

    @Test
    void retiredAndActiveOverlapIsAmbiguousToo() {
        InMemoryRuleVersionPort port = withGlobal()
                .add(Bundles.global("DISC-OLD", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-01"), RuleStatus.RETIRED, Y2026.body()));
        assertThat(failure(port).failure()).isEqualTo(ResolutionFailure.AMBIGUOUS);
    }

    @Test
    void twoTenantRulesAreAmbiguous() {
        InMemoryRuleVersionPort port = withGlobal()
                .add(Bundles.tenant("HOUSE-A", FROM, null, RuleStatus.ACTIVE, json("{\"signDeadlineDays\": 10}")))
                .add(Bundles.tenant("HOUSE-B", FROM, null, RuleStatus.ACTIVE, json("{\"signDeadlineDays\": 9}")));
        assertThat(failure(port).failure()).isEqualTo(ResolutionFailure.AMBIGUOUS);
    }

    @Test
    void asOfIsMandatory() {
        assertThatThrownBy(() -> new RuleResolver(withGlobal()).resolve(T, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void boundaryDateSelectsExactlyOneRule() {
        RuleBundle y2027 = Bundles.rule(Bundles.DISC_2027_01);
        InMemoryRuleVersionPort port = new InMemoryRuleVersionPort()
                .add(Bundles.global(Y2026, RuleStatus.RETIRED, y2027.applyFrom()))
                .add(Bundles.global(y2027, RuleStatus.ACTIVE, null));
        RuleResolver resolver = new RuleResolver(port);
        EffectiveRule before = resolver.resolve(T, LocalDate.parse("2026-12-31"));
        EffectiveRule after = resolver.resolve(T, LocalDate.parse("2027-01-01"));
        assertThat(before.globalRuleVersionId()).isEqualTo(Y2026.ruleVersionId());
        assertThat(before.minCompare()).isEqualTo(3);
        assertThat(before.managerConfirmMode()).isEqualTo(ManagerConfirmMode.REQUIRED);
        assertThat(after.globalRuleVersionId()).isEqualTo(y2027.ruleVersionId());
        assertThat(after.minCompare()).isEqualTo(4);
        assertThat(after.managerConfirmMode()).isEqualTo(ManagerConfirmMode.OFF);
    }

    // ------------------------------------------------------------------ C3

    @Test
    void noTenantRuleMeansGlobalAsIs() {
        EffectiveRule rule = new RuleResolver(withGlobal()).resolve(T, D);
        assertThat(rule.tenantRuleVersion()).isEmpty();
        assertThat(rule.body()).isEqualTo(Y2026.body());
        assertThat(rule.bodyHash()).isEqualTo(Y2026.bodyHash());
        assertThat(rule.describe()).contains("asOf=2026-09-23", "global=DISC-2026-07", "tenant=-", Y2026.bodyHash());
    }

    @Test
    void overridableKeysAreMergedAndNestedObjectsReplacedWhole() {
        JsonNode house = json("""
                {"signDeadlineDays": 10,
                 "channels": {"TOUCH_PAD": true, "REMOTE_LINK": true, "PAPER_SCAN": false, "CERTIFIED_ESIGN": false},
                 "identityCheck": {"REMOTE_LINK": ["LINK_POSSESSION", "BIRTH_DATE"], "maxFailures": 3}}
                """);
        EffectiveRule rule = new RuleResolver(withGlobal().add(Bundles.tenant("HOUSE", FROM, null, RuleStatus.ACTIVE, house)))
                .resolve(T, D);
        assertThat(rule.tenantRuleVersion()).hasValueSatisfying(id -> assertThat(id.value()).isEqualTo("HOUSE"));
        assertThat(rule.signDeadlineDays()).isEqualTo(10);
        assertThat(rule.channels()).containsEntry(SignatureChannel.PAPER_SCAN, false);
        // 깊은 병합이 아니다: TENANT의 identityCheck에 TOUCH_PAD가 없으므로 병합 결과에도 없다.
        assertThat(rule.identityCheckMaxFailures()).isEqualTo(3);
        assertThatThrownBy(() -> rule.identityCheck(SignatureChannel.TOUCH_PAD)).isInstanceOf(MissingRuleKeyException.class);
        // 열리지 않은 키는 GLOBAL 값 그대로
        assertThat(rule.minCompare()).isEqualTo(3);
        ObjectNode expected = (ObjectNode) Y2026.body();
        house.propertyNames().forEach(k -> expected.set(k, house.get(k)));
        assertThat(rule.bodyHash()).isEqualTo(Sha256.of(Canonicalizer.canonicalize(expected)));
    }

    @Test
    void keysOutsideTenantOverridableAreRejectedNotIgnored() {
        JsonNode loosening = json("{\"signDeadlineDays\": 10, \"minCompare\": 2, \"managerConfirmMode\": \"OFF\"}");
        RuleResolutionException e = failure(withGlobal().add(Bundles.tenant("HOUSE", FROM, null, RuleStatus.ACTIVE, loosening)));
        assertThat(e.failure()).isEqualTo(ResolutionFailure.DISALLOWED_OVERRIDE);
        assertThat(e).hasMessageContaining("[managerConfirmMode, minCompare]");
    }

    @Test
    void theOpenKeyListItselfIsData() {
        ObjectNode narrower = (ObjectNode) Y2026.body();
        narrower.putArray("tenantOverridable").add("remoteLinkTtlHours");
        InMemoryRuleVersionPort port = new InMemoryRuleVersionPort()
                .add(Bundles.global("DISC-NARROW", FROM, null, RuleStatus.ACTIVE, narrower))
                .add(Bundles.tenant("HOUSE", FROM, null, RuleStatus.ACTIVE, json("{\"signDeadlineDays\": 10}")));
        assertThat(failure(port).failure()).isEqualTo(ResolutionFailure.DISALLOWED_OVERRIDE);
    }

    @Test
    void accessorsHaveNoDefaults() {
        ObjectNode body = (ObjectNode) Y2026.body();
        body.remove("reasonTextMaxLength");
        EffectiveRule rule = EffectiveRule.of(D, Y2026.ruleVersionId(), null, body);
        assertThatThrownBy(rule::reasonTextMaxLength).isInstanceOf(MissingRuleKeyException.class)
                .hasMessageContaining("reasonTextMaxLength");
        body.put("minCompare", "3");
        assertThatThrownBy(() -> EffectiveRule.of(D, Y2026.ruleVersionId(), null, body).minCompare())
                .isInstanceOf(MissingRuleKeyException.class);
    }

    @Test
    void bodyHashMustMatchTheBody() {
        assertThatThrownBy(() -> new EffectiveRule(D, Y2026.ruleVersionId(), null, Y2026.body(), "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
