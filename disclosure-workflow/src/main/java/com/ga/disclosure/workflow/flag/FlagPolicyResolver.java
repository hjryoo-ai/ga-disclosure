package com.ga.disclosure.workflow.flag;

import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.FlagTypePolicy;
import com.ga.disclosure.rules.resolve.MissingRuleKeyException;
import com.ga.disclosure.rules.resolve.RuleResolutionException;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.authz.NotAnEntry;
import com.ga.platform.core.tenant.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * 열린 시각(KST 날짜)의 ACTIVE 룰 {@code complianceQueue.types[type]}을 읽는다(6B 계획 §7). 기한 = 열린 시각 + {@code slaHours}(null이면 없음).
 * 룰이 없거나(예: {@code RULE_ACTIVATION_MISSED} — ACTIVE 룰이 비었다) 모호하거나 그 유형이 없으면 닫힌 쪽 기본값이다. 규칙은 데이터에서만 온다.
 */
public final class FlagPolicyResolver implements FlagPolicySource {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RuleResolver rules;

    public FlagPolicyResolver(RuleResolver rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @Override
    @NotAnEntry("flag raise plumbing: copies today's rule policy for the flag type inside the raising use case's transaction; reads rule data only")
    public FlagPolicy at(TenantId tenant, String type, Instant raisedAt) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(raisedAt, "raisedAt");
        FlagTypePolicy p;
        try {
            EffectiveRule rule = rules.resolve(tenant, LocalDate.ofInstant(raisedAt, SEOUL));
            p = rule.flagPolicy(type);
        } catch (RuleResolutionException | MissingRuleKeyException e) {
            return FlagPolicy.failClosed();
        }
        Optional<Instant> due = p.slaHours().isPresent() ? Optional.of(raisedAt.plus(Duration.ofHours(p.slaHours().getAsInt()))) : Optional.empty();
        return new FlagPolicy(p.assignedRole(), p.visibleToAgent(), due, true);
    }
}
