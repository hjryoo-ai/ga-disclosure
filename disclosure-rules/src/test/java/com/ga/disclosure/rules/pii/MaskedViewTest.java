package com.ga.disclosure.rules.pii;

import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.testing.Bundles;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 마스킹 자릿수는 룰 데이터다 — 같은 값이 룰 본문만 바꾸면 다르게 가려진다(코드 diff 0). */
class MaskedViewTest {

    private static final LocalDate D = LocalDate.parse("2026-09-23");
    private static final EffectiveRule RULE = RuleResolver.merge(D, Bundles.global(Bundles.rule(Bundles.DISC_2026_07), RuleStatus.ACTIVE, null),
            null);

    @Test
    void bundleDefaultsMaskEachField() {
        assertThat(MaskedView.of(CustomerName.of("홍길동"), RULE)).isEqualTo("홍*동");
        assertThat(MaskedView.of(PhoneNumber.of("010-5550-0101"), RULE)).isEqualTo("010****0101");
        assertThat(MaskedView.of(BirthDate.parse("1944-03-05"), RULE)).isEqualTo("**********");
    }

    @Test
    void changingOnlyTheRuleDataChangesTheMask() {
        ObjectNode body = (ObjectNode) RULE.body();
        ((ObjectNode) body.at("/masking/phone")).put("keepFirst", 0).put("maskChar", "#");
        ((ObjectNode) body.at("/masking/birthDate")).put("keepFirst", 4);
        EffectiveRule house = EffectiveRule.of(D, RULE.globalRuleVersionId(), null, body);
        assertThat(MaskedView.of(PhoneNumber.of("010-5550-0101"), house)).isEqualTo("#######0101");
        assertThat(MaskedView.of(BirthDate.parse("1944-03-05"), house)).isEqualTo("1944******");
    }
}
