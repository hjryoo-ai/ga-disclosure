package com.ga.disclosure.rules.resolve;

import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.testing.Bundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 7 ⑥ 룰 키 {@code preview.watermark}: 번들 값 그대로 읽히고, 치환자는 닫힌 집합({@code {role}}·{@code {at}})뿐이며, 빠지면 기본값 없이 예외다.
 * 기대 문구는 번들 데이터에서 만든다(시험에 문구 리터럴 없음).
 */
class PreviewWatermarkRuleTest {

    private static EffectiveRule rule(Consumer<ObjectNode> edit) {
        ObjectNode body = (ObjectNode) Bundles.rule(Bundles.DISC_2026_07).body().deepCopy();
        edit.accept(body);
        return EffectiveRule.of(LocalDate.parse("2026-10-10"), RuleVersionId.of("DISC-2026-07"), null, body);
    }

    @Test
    void theBundleValueIsReadAndRendered() {
        JsonNode data = Bundles.rule(Bundles.DISC_2026_07).body().at("/preview/watermark");
        PreviewWatermarkRule wm = rule(b -> { }).previewWatermark();
        assertThat(wm.text()).isEqualTo(data.get("text").asString());
        assertThat(wm.roleLabels()).containsOnlyKeys("AGENT", "MANAGER", "COMPLIANCE");
        assertThat(wm.render("MANAGER", "2026-10-10 09:30")).isEqualTo(data.get("text").asString()
                .replace("{role}", data.at("/roleLabels/MANAGER").asString()).replace("{at}", "2026-10-10 09:30"));
        assertThatThrownBy(() -> wm.render("OPERATOR", "x")).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{who}", "{role", "x } y", "{{role}}"})
    void placeholdersAreAClosedSet(String text) {
        assertThatThrownBy(() -> new PreviewWatermarkRule(text, Map.of("AGENT", "a"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.at("/preview/watermark")).put("text", text)).previewWatermark())
                .isInstanceOf(MissingRuleKeyException.class);
    }

    @Test
    void aMissingKeyHasNoDefault() {
        assertThatThrownBy(() -> rule(b -> b.remove("preview")).previewWatermark()).isInstanceOf(MissingRuleKeyException.class);
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.at("/preview/watermark")).remove("roleLabels")).previewWatermark())
                .isInstanceOf(MissingRuleKeyException.class);
    }
}
