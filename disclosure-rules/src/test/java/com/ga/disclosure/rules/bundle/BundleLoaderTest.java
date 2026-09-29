package com.ga.disclosure.rules.bundle;

import com.ga.disclosure.rules.testing.Bundles;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 번들 로더: 정본 번들 3종이 스키마·ID·해시 규칙을 통과하고, 변조·형식 위반은 거부된다(Phase 1 C12의 파일 쪽).
 * "사규는 규제를 완화할 수 없다"의 데이터 표현(tenantOverridable 금지 목록)과 관리자 확인 모드↔서명자 집합 정합도 스키마가 강제한다.
 */
class BundleLoaderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ObjectNode tree(String relative) {
        return (ObjectNode) JSON.readTree(Bundles.text(relative));
    }

    private static void assertInvalid(ObjectNode bundle, String expectedFragment) {
        assertThatThrownBy(() -> BundleLoader.parse("test", JSON.writeValueAsString(bundle)))
                .isInstanceOf(InvalidBundleException.class)
                .satisfies(e -> assertThat(((InvalidBundleException) e).problems().toString()).contains(expectedFragment));
    }

    @ParameterizedTest
    @ValueSource(strings = {Bundles.DISC_2026_07, Bundles.DISC_2027_01, Bundles.STANDARD_V1})
    void canonicalBundlesLoadAndHashMatchesIdAndRecomputation(String relative) {
        Bundle bundle = Bundles.load(relative);
        String recomputed = Sha256.of(Canonicalizer.canonicalize(tree(relative).get("body")));
        assertThat(bundle.bodyHash()).isEqualTo(recomputed).hasSize(64);
        assertThat(bundle.bundleId()).isEqualTo(BundleLoader.idPrefix(bundle) + "@" + recomputed.substring(0, 12));
    }

    @Test
    void disc202701SupersedesDisc202607WithRegulationChangesOnly() {
        RuleBundle y2026 = Bundles.rule(Bundles.DISC_2026_07);
        RuleBundle y2027 = Bundles.rule(Bundles.DISC_2027_01);
        assertThat(y2027.supersedes()).isEqualTo(y2026.ruleVersionId());
        assertThat(y2027.applyFrom()).hasToString("2027-01-01");
        assertThat(y2027.body().get("minCompare").intValue()).isEqualTo(4);
        assertThat(y2027.body().get("managerConfirmMode").asString()).isEqualTo("OFF");
    }

    @Test
    void whitespaceAndKeyOrderDoNotChangeTheHash() {
        ObjectNode reordered = JSON.createObjectNode();
        ObjectNode original = tree(Bundles.DISC_2026_07);
        java.util.List<String> keys = new java.util.ArrayList<>(original.propertyNames());
        java.util.Collections.reverse(keys);
        keys.forEach(k -> reordered.set(k, original.get(k)));
        Bundle bundle = BundleLoader.parse("reordered", JSON.writerWithDefaultPrettyPrinter().writeValueAsString(reordered));
        assertThat(bundle.bodyHash()).isEqualTo(Bundles.load(Bundles.DISC_2026_07).bodyHash());
    }

    @Test
    void tamperedBodyIsRejectedByTheIdHashCheck() {
        ObjectNode bundle = tree(Bundles.DISC_2026_07);
        ((ObjectNode) bundle.get("body")).put("minCompare", 2);
        assertInvalid(bundle, "does not match the recomputed");
    }

    @Test
    void idPrefixMustMatchTheRuleVersionId() {
        ObjectNode bundle = tree(Bundles.DISC_2026_07);
        bundle.put("ruleVersionId", "DISC-2026-08");
        assertInvalid(bundle, "does not match the recomputed DISC-2026-08@");
    }

    @Test
    void duplicateKeysAreRejected() {
        String text = Bundles.text(Bundles.DISC_2026_07).replaceFirst("\"kind\": \"RULE\",", "\"kind\": \"RULE\", \"kind\": \"RULE\",");
        assertThatThrownBy(() -> BundleLoader.parse("dup", text)).isInstanceOf(InvalidBundleException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"minCompare", "signerSet", "managerConfirmMode", "validations", "tenantOverridable", "allowedTieBreaks",
            "gradeRequired", "subjectRule", "allowedGradingPolicies", "allowedRankingPolicies"})
    void regulationCoreKeysCannotBeOpenedToTenants(String key) {
        ObjectNode bundle = tree(Bundles.DISC_2026_07);
        ((tools.jackson.databind.node.ArrayNode) bundle.get("body").get("tenantOverridable")).add(key);
        assertInvalid(bundle, "tenantOverridable");
    }

    @Test
    void managerConfirmModeAndSignerSetMustAgree() {
        ObjectNode offWithManager = tree(Bundles.DISC_2026_07);
        ((ObjectNode) offWithManager.get("body")).put("managerConfirmMode", "OFF");
        assertInvalid(offWithManager, "signerSet");

        ObjectNode requiredWithoutManager = tree(Bundles.DISC_2027_01);
        ((ObjectNode) requiredWithoutManager.get("body")).put("managerConfirmMode", "REQUIRED");
        assertInvalid(requiredWithoutManager, "signerSet");
    }

    @Test
    void signOrderIsClosed() {
        ObjectNode bundle = tree(Bundles.DISC_2026_07);
        ((ObjectNode) bundle.get("body")).put("signOrder", "RANDOM");
        assertInvalid(bundle, "signOrder");
    }

    @Test
    void templateBundleRequiresRenderScopeAndNoRuleFields() {
        ObjectNode noScope = tree(Bundles.STANDARD_V1);
        JsonNode field = noScope.get("body").get("fields").get(0);
        ((ObjectNode) field.get("render")).remove("scope");
        assertInvalid(noScope, "scope");

        ObjectNode mixed = tree(Bundles.STANDARD_V1);
        mixed.put("ruleVersionId", "DISC-2026-07");
        assertInvalid(mixed, "ruleVersionId");
    }

    @Test
    void ruleBundleCannotSupersedeTemplateShape() {
        ObjectNode bundle = tree(Bundles.DISC_2027_01);
        ((ObjectNode) bundle.get("supersedes")).put("version", 1);
        assertInvalid(bundle, "supersedes");
    }
}
