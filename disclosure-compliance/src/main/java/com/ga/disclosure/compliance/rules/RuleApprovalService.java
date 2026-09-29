package com.ga.disclosure.compliance.rules;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.bundle.RuleSchemas;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolutionException;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 사규(TENANT) 룰 승인: DRAFT → APPROVED. 승인 전에 사규의 적용 구간과 겹치는 모든 GLOBAL 복제본 각각에 대해
 * ① 사규 키가 그 GLOBAL의 {@code tenantOverridable} 안에 있고 ② 병합 결과가 룰 스키마를 통과하는지 검사한다. 하나라도 어기면 거부.
 * 해석기도 해석 시점에 같은 규칙을 다시 적용한다(이후 배포된 GLOBAL이 키를 닫을 수 있으므로).
 * 이미 승인된(APPROVED·ACTIVE·RETIRED) 사규에 대한 재요청은 아무것도 바꾸지 않는 no-op이다(배포 재실행과 같은 멱등성).
 */
public final class RuleApprovalService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RuleVersionStore rules;
    private final AuditPort audit;
    private final TenantTransactions transactions;
    private final Clock clock;

    public RuleApprovalService(RuleVersionStore rules, AuditPort audit, TenantTransactions transactions, Clock clock) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 승인했으면 APPROVED, 이미 승인 이후 상태였으면 그 상태를 돌려준다(no-op). */
    public RuleStatus approve(TenantId tenant, RuleVersionId id, Operator operator) {
        return transactions.inTenant(tenant, () -> {
            RuleVersion draft = rules.find(id)
                    .orElseThrow(() -> new GovernanceRejectedException(tenant + ": rule " + id + " does not exist"));
            if (draft.scope() != RuleScope.TENANT) {
                throw new GovernanceRejectedException(tenant + ": only a TENANT rule can be approved (" + id + " is " + draft.scope() + ")");
            }
            if (draft.status() != RuleStatus.DRAFT) {
                return draft.status();
            }
            List<RuleVersion> globals = rules.findGlobalReplicas().stream().filter(g -> overlaps(g, draft)).toList();
            if (globals.isEmpty()) {
                throw new GovernanceRejectedException(tenant + ": no GLOBAL rule covers " + id + " — a house rule only amends a regulation");
            }
            ArrayNode checked = JSON.createArrayNode();
            for (RuleVersion global : globals) {
                LocalDate asOf = global.applyFrom().isAfter(draft.applyFrom()) ? global.applyFrom() : draft.applyFrom();
                EffectiveRule merged;
                try {
                    merged = RuleResolver.merge(asOf, global, draft);
                } catch (RuleResolutionException e) {
                    throw new GovernanceRejectedException(e.getMessage());
                }
                List<String> errors = RuleSchemas.validateRuleBody(merged.body());
                if (!errors.isEmpty()) {
                    throw new GovernanceRejectedException(tenant + ": " + id + " merged onto " + global.id() + " violates the rule schema "
                            + errors);
                }
                checked.add(global.id().value());
            }
            if (rules.approve(id, operator.subject(), clock.instant()) != 1) {
                throw new GovernanceRejectedException(tenant + ": " + id + " was not in DRAFT");
            }
            ObjectNode detail = JSON.createObjectNode();
            detail.set("checkedAgainst", checked);
            audit.append(new AuditEntry(clock.instant(), operator.subject(), Operator.ROLE, AuditAction.RULE_APPROVE, "RULE_VERSION",
                    id.value(), detail));
            return RuleStatus.APPROVED;
        });
    }

    private static boolean overlaps(RuleVersion a, RuleVersion b) {
        boolean aEndsAfterBStarts = a.applyTo() == null || a.applyTo().isAfter(b.applyFrom());
        boolean bEndsAfterAStarts = b.applyTo() == null || b.applyTo().isAfter(a.applyFrom());
        return aEndsAfterBStarts && bEndsAfterAStarts;
    }
}
