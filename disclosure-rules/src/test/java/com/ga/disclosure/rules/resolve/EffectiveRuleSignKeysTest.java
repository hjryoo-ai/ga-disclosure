package com.ga.disclosure.rules.resolve;

import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.RetentionAnchor;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.testing.Bundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.OptionalInt;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 룰 키(3B 수용심사 §2-2·§3-3, 4 계획 승인 Q3·Q5·Q8): 번들 값이 그대로 읽히고, 빠지거나 형식이 틀리면 기본값 없이 예외다.
 * IP 재사용 지표만 선택 키(없으면 끔).
 */
class EffectiveRuleSignKeysTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate D = LocalDate.parse("2026-09-23");

    private static EffectiveRule rule(Consumer<ObjectNode> edit) {
        ObjectNode body = (ObjectNode) Bundles.rule(Bundles.DISC_2026_07).body().deepCopy();
        edit.accept(body);
        return EffectiveRule.of(D, RuleVersionId.of("DISC-2026-07"), null, body);
    }

    private static final EffectiveRule BUNDLE = rule(b -> { });

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void bundleValuesAreReadAsData() {
        assertThat(BUNDLE.channel(SignatureChannel.PAPER_SCAN)).isEqualTo(new ChannelPolicy(true, true));
        assertThat(BUNDLE.channel(SignatureChannel.TOUCH_PAD)).isEqualTo(new ChannelPolicy(true, false));
        assertThat(BUNDLE.channel(SignatureChannel.CERTIFIED_ESIGN).enabled()).isFalse();
        assertThat(BUNDLE.channels()).doesNotContainKey(SignatureChannel.SSO);
        assertThat(BUNDLE.sessionTtlMinutes(SignatureChannel.TOUCH_PAD)).isEqualTo(30);
        assertThat(BUNDLE.sessionTtlMinutes(SignatureChannel.PAPER_SCAN)).isEqualTo(10080);
        assertThat(BUNDLE.agentSignMethod()).isEqualTo(SignatureMethod.DRAWN);
        assertThat(BUNDLE.identityMethods(SignatureChannel.REMOTE_LINK)).containsExactly(IdentityMethod.LINK_POSSESSION, IdentityMethod.BIRTH_DATE);
        assertThat(BUNDLE.identityMethods(SignatureChannel.TOUCH_PAD))
                .containsExactly(IdentityMethod.AGENT_FACE_TO_FACE, IdentityMethod.SCROLL_COMPLETE);
        assertThat(BUNDLE.sameDeviceDistinctCustomersPerDay()).isEqualTo(2);
        assertThat(BUNDLE.sameIpDistinctCustomersPerDay()).isEqualTo(OptionalInt.empty());
        assertThat(BUNDLE.minSecondsFromSendToSign()).isEqualTo(60);
        assertThat(BUNDLE.retentionAnchors()).containsExactly(RetentionAnchor.SEAL, RetentionAnchor.COMPLETION, RetentionAnchor.CONTRACT_DATE);
        assertThat(BUNDLE.voidReasons()).extracting(LifecycleReasonRule::code)
                .containsExactly("CUSTOMER_CANCELLED", "WRITTEN_IN_ERROR", "DUPLICATE", "OTHER");
        assertThat(BUNDLE.voidReasons()).filteredOn(LifecycleReasonRule::requiresText).extracting(LifecycleReasonRule::code).containsExactly("OTHER");
        assertThat(BUNDLE.supersedeReasons()).extracting(LifecycleReasonRule::code)
                .containsExactly("CONTENT_ERROR", "PRODUCT_DATA_CORRECTED", "OTHER");
        assertThat(BUNDLE.lifecycleReasonTextMaxLength()).isEqualTo(500);
    }

    @Test
    void dataChangesBehaviourWithoutCode() {
        EffectiveRule changed = rule(b -> {
            b.set("agentSignMethod", json("\"SSO_APPROVAL\""));
            ((ObjectNode) b.get("proxySignatureDetection")).set("sameIpDistinctCustomersPerDay", json("5"));
            ((ObjectNode) b.get("channels")).set("PAPER_SCAN", json("{\"enabled\": false}"));
            b.set("retentionAnchors", json("[\"COMPLETION\"]"));
        });
        assertThat(changed.agentSignMethod()).isEqualTo(SignatureMethod.SSO_APPROVAL);
        assertThat(changed.sameIpDistinctCustomersPerDay()).hasValue(5);
        assertThat(changed.channel(SignatureChannel.PAPER_SCAN)).isEqualTo(new ChannelPolicy(false, false));
        assertThat(changed.retentionAnchors()).containsExactly(RetentionAnchor.COMPLETION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"voidReasons", "supersedeReasons", "lifecycleReasonTextMaxLength", "retentionAnchors", "sessionTtlMinutes",
            "agentSignMethod", "channels"})
    void missingKeysFailWithoutDefaults(String key) {
        EffectiveRule missing = rule(b -> b.remove(key));
        assertThatThrownBy(() -> {
            switch (key) {
                case "voidReasons" -> missing.voidReasons();
                case "supersedeReasons" -> missing.supersedeReasons();
                case "lifecycleReasonTextMaxLength" -> missing.lifecycleReasonTextMaxLength();
                case "retentionAnchors" -> missing.retentionAnchors();
                case "sessionTtlMinutes" -> missing.sessionTtlMinutes(SignatureChannel.TOUCH_PAD);
                case "agentSignMethod" -> missing.agentSignMethod();
                default -> missing.channel(SignatureChannel.TOUCH_PAD);
            }
        }).isInstanceOf(MissingRuleKeyException.class);
    }

    @Test
    void malformedValuesFail() {
        assertThatThrownBy(() -> rule(b -> b.set("agentSignMethod", json("\"UPLOADED_SCAN\""))).agentSignMethod())
                .isInstanceOf(MissingRuleKeyException.class);
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.get("channels")).set("TOUCH_PAD", json("true"))).channel(SignatureChannel.TOUCH_PAD))
                .as("Phase 3 boolean form is gone (approval Q5)")
                .isInstanceOf(MissingRuleKeyException.class);
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.get("channels")).set("TOUCH_PAD", json("{}"))).channel(SignatureChannel.TOUCH_PAD))
                .isInstanceOf(MissingRuleKeyException.class);
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.get("proxySignatureDetection")).set("sameIpDistinctCustomersPerDay", json("\"3\"")))
                .sameIpDistinctCustomersPerDay())
                .isInstanceOf(MissingRuleKeyException.class);
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.get("sessionTtlMinutes")).remove("PAPER_SCAN")).sessionTtlMinutes(SignatureChannel.PAPER_SCAN))
                .isInstanceOf(MissingRuleKeyException.class);
    }
}
