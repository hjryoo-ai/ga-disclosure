package com.ga.disclosure.compliance.rules;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 룰 활성화 일 배치(설계서 §5, 주입된 {@link Clock}의 날짜). 테넌트마다 한 트랜잭션.
 * <ol>
 *   <li>먼저 RETIRED: ACTIVE이고 {@code apply_to ≤ 오늘} → RETIRED (배타 제약 충돌 방지를 위해 활성화보다 먼저).</li>
 *   <li>다음 ACTIVE: APPROVED이고 {@code apply_from ≤ 오늘} → ACTIVE. 단 적용 구간이 이미 끝난 APPROVED({@code apply_to ≤ 오늘})는
 *       활성화하지 않고 보고한다(과거 구간을 사후에 시행 중으로 만들지 않는다).</li>
 * </ol>
 * 스케줄 등록은 Phase 6이며 지금은 서비스 + 운영자 CLI({@code rules activate --as-of}).
 */
public final class RuleActivationJob {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RuleVersionStore rules;
    private final AuditPort audit;
    private final TenantTransactions transactions;
    private final Clock clock;

    public RuleActivationJob(RuleVersionStore rules, AuditPort audit, TenantTransactions transactions, Clock clock) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 주입된 시계의 오늘(시계의 시간대 기준)로 실행한다. */
    public ActivationReport run(TenantId tenant, Operator operator) {
        return runAsOf(tenant, LocalDate.now(clock), operator);
    }

    public ActivationReport runAsOf(TenantId tenant, LocalDate today, Operator operator) {
        return transactions.inTenant(tenant, () -> {
            List<RuleVersionId> retired = new ArrayList<>();
            for (RuleVersion r : rules.findByStatus(RuleStatus.ACTIVE)) {
                if (r.applyTo() != null && !r.applyTo().isAfter(today)) {
                    step(r, RuleStatus.ACTIVE, RuleStatus.RETIRED, AuditAction.RULE_RETIRE, today, operator);
                    retired.add(r.id());
                }
            }
            List<RuleVersionId> activated = new ArrayList<>();
            List<RuleVersionId> expired = new ArrayList<>();
            for (RuleVersion r : rules.findByStatus(RuleStatus.APPROVED)) {
                if (r.applyFrom().isAfter(today)) {
                    continue;
                }
                if (r.applyTo() != null && !r.applyTo().isAfter(today)) {
                    expired.add(r.id());
                    continue;
                }
                step(r, RuleStatus.APPROVED, RuleStatus.ACTIVE, AuditAction.RULE_ACTIVATE, today, operator);
                activated.add(r.id());
            }
            return new ActivationReport(tenant, today, retired, activated, expired);
        });
    }

    private void step(RuleVersion rule, RuleStatus from, RuleStatus to, AuditAction action, LocalDate today, Operator operator) {
        if (rules.transition(rule.id(), from, to) != 1) {
            throw new GovernanceRejectedException(rule.id() + " was not " + from);
        }
        ObjectNode detail = JSON.createObjectNode();
        detail.put("from", from.name()).put("to", to.name()).put("asOf", today.toString()).put("scope", rule.scope().name());
        audit.append(new AuditEntry(clock.instant(), operator.subject(), Operator.ROLE, action, "RULE_VERSION", rule.id().value(), detail));
    }
}
