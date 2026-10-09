package com.ga.disclosure.workflow.flag;

import com.ga.disclosure.rules.resolve.FlagAssignee;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.testing.InMemoryRuleVersionPort;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 6B 계획 §7: 열린 시각(KST 날짜)의 ACTIVE 룰에서 복사, 해석할 수 없으면 닫힌 쪽 기본값(준법·보이지 않음·기한 없음). */
class FlagPolicyResolverTest {

    static final TenantId T = TenantId.of("T_FLAG");
    static final Instant AT = Instant.parse("2026-09-23T01:00:00Z");

    @Test
    void theActiveRuleIsCopied() {
        InMemoryRuleVersionPort port = new InMemoryRuleVersionPort().add(Bundles.global(Bundles.rule(Bundles.DISC_2026_07), RuleStatus.ACTIVE, null));
        FlagPolicyResolver resolver = new FlagPolicyResolver(new RuleResolver(port));
        assertThat(resolver.at(T, "PAPER_SCAN_REVIEW", AT)).isEqualTo(new FlagPolicy(FlagAssignee.MANAGER, false, Optional.empty(), true));
        assertThat(resolver.at(T, "CHAIN_BROKEN", AT)).isEqualTo(new FlagPolicy(FlagAssignee.COMPLIANCE, false, Optional.empty(), true));
    }

    @Test
    void withoutAnActiveRuleTheDefaultIsFailClosed() {
        FlagPolicy p = new FlagPolicyResolver(new RuleResolver(new InMemoryRuleVersionPort())).at(T, "RULE_ACTIVATION_MISSED", AT);
        assertThat(p).isEqualTo(FlagPolicy.failClosed());
        assertThat(p.fromRule()).isFalse();
        assertThat(p.assignedRole()).isEqualTo(FlagAssignee.COMPLIANCE);
        assertThat(p.visibleToAgent()).isFalse();
        assertThat(p.dueAt()).isEmpty();
    }
}
