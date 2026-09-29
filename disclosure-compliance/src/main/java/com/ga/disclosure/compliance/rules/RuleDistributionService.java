package com.ga.disclosure.compliance.rules;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.rules.bundle.Bundle;
import com.ga.disclosure.rules.bundle.RuleBundle;
import com.ga.disclosure.rules.bundle.TemplateBundle;
import com.ga.disclosure.rules.template.FormTemplate;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 규제 번들 배포: 번들을 테넌트마다 같은 ID로 복제한다(설계서 §5·§6.2). 테넌트마다 한 트랜잭션.
 * <ol>
 *   <li>같은 ID가 이미 있고 번들 ID·해시가 같으면 no-op(감사 행 {@code outcome=NOOP} 1건). 해시가 다르면 거부 — 규제가 바뀌었으면
 *       새 ID다. 덮어쓰기는 존재하지 않는다.</li>
 *   <li>{@code supersedes}가 있으면 선행 룰·서식의 {@code apply_to}를 이 번들의 {@code applyFrom}으로 닫는다. 선행이 없거나 이미
 *       다른 날짜로 닫혀 있으면 거부.</li>
 *   <li>복제본 삽입(룰은 {@code status=APPROVED}), 감사 기록 {@code RULE_DISTRIBUTE}.</li>
 * </ol>
 */
public final class RuleDistributionService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RuleVersionStore rules;
    private final FormTemplateStore templates;
    private final AuditPort audit;
    private final TenantTransactions transactions;
    private final Clock clock;

    public RuleDistributionService(RuleVersionStore rules, FormTemplateStore templates, AuditPort audit,
                                   TenantTransactions transactions, Clock clock) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 테넌트마다 독립 트랜잭션으로 배포하고 결과를 모은다(한 곳의 거부가 다른 곳을 되돌리지 않는다). */
    public List<DistributionOutcome> distribute(Bundle bundle, List<TenantId> tenants, Operator operator) {
        List<DistributionOutcome> out = new ArrayList<>();
        for (TenantId tenant : tenants) {
            try {
                out.add(distribute(bundle, tenant, operator));
            } catch (GovernanceRejectedException e) {
                out.add(new DistributionOutcome(tenant, bundle.bundleId(), DistributionOutcome.Result.REJECTED, e.getMessage()));
            }
        }
        return List.copyOf(out);
    }

    /** 한 테넌트에 배포한다. 거부되면 {@link GovernanceRejectedException}(트랜잭션 롤백). */
    public DistributionOutcome distribute(Bundle bundle, TenantId tenant, Operator operator) {
        return transactions.inTenant(tenant, () -> switch (bundle) {
            case RuleBundle rule -> distributeRule(rule, tenant, operator);
            case TemplateBundle template -> distributeTemplate(template, tenant, operator);
        });
    }

    private DistributionOutcome distributeRule(RuleBundle bundle, TenantId tenant, Operator operator) {
        Optional<RuleVersion> existing = rules.find(bundle.ruleVersionId());
        if (existing.isPresent()) {
            RuleVersion e = existing.get();
            if (e.scope() == RuleScope.GLOBAL && bundle.bodyHash().equals(e.bundleHash()) && bundle.bundleId().equals(e.sourceBundleId())) {
                record(operator, "RULE_VERSION", bundle.ruleVersionId().value(), detail(bundle, "NOOP"));
                return new DistributionOutcome(tenant, bundle.bundleId(), DistributionOutcome.Result.NOOP, "already distributed");
            }
            throw new GovernanceRejectedException(tenant + ": rule " + bundle.ruleVersionId() + " already exists with bundle "
                    + e.sourceBundleId() + " (" + e.bundleHash() + "); a changed regulation needs a new rule_version_id");
        }
        ObjectNode detail = detail(bundle, "INSERTED");
        if (bundle.supersedes() != null) {
            RuleVersion predecessor = rules.find(bundle.supersedes())
                    .filter(p -> p.scope() == RuleScope.GLOBAL)
                    .orElseThrow(() -> new GovernanceRejectedException(tenant + ": superseded GLOBAL rule " + bundle.supersedes()
                            + " does not exist"));
            closePredecessor(tenant, predecessor.id().value(), predecessor.applyTo(), bundle.applyFrom(),
                    () -> rules.closeApplyTo(predecessor.id(), bundle.applyFrom()));
            detail.put("supersedes", predecessor.id().value()).put("closedApplyTo", bundle.applyFrom().toString());
        }
        rules.insert(new RuleVersion(bundle.ruleVersionId(), RuleScope.GLOBAL, bundle.applyFrom(), bundle.applyTo(), RuleStatus.APPROVED,
                operator.subject(), clock.instant(), bundle.body(), bundle.bundleId(), bundle.bodyHash()));
        record(operator, "RULE_VERSION", bundle.ruleVersionId().value(), detail);
        return new DistributionOutcome(tenant, bundle.bundleId(), DistributionOutcome.Result.INSERTED, "inserted as APPROVED");
    }

    private DistributionOutcome distributeTemplate(TemplateBundle bundle, TenantId tenant, Operator operator) {
        String target = bundle.template().templateId() + ".v" + bundle.template().version();
        Optional<FormTemplate> existing = templates.find(bundle.template());
        if (existing.isPresent()) {
            FormTemplate e = existing.get();
            if (bundle.bodyHash().equals(e.bundleHash()) && bundle.bundleId().equals(e.sourceBundleId())) {
                record(operator, "FORM_TEMPLATE", target, detail(bundle, "NOOP"));
                return new DistributionOutcome(tenant, bundle.bundleId(), DistributionOutcome.Result.NOOP, "already distributed");
            }
            throw new GovernanceRejectedException(tenant + ": template " + target + " already exists with bundle " + e.sourceBundleId()
                    + "; a changed template needs a new version");
        }
        ObjectNode detail = detail(bundle, "INSERTED");
        if (bundle.supersedes() != null) {
            FormTemplate predecessor = templates.find(bundle.supersedes())
                    .orElseThrow(() -> new GovernanceRejectedException(tenant + ": superseded template " + bundle.supersedes()
                            + " does not exist"));
            closePredecessor(tenant, bundle.supersedes().toString(), predecessor.applyTo(), bundle.applyFrom(),
                    () -> templates.closeApplyTo(predecessor.ref(), bundle.applyFrom()));
            detail.put("supersedes", bundle.supersedes().templateId() + ".v" + bundle.supersedes().version())
                    .put("closedApplyTo", bundle.applyFrom().toString());
        }
        JsonNode body = bundle.body();
        templates.insert(new FormTemplate(bundle.template(), bundle.templateType(), bundle.applyFrom(), bundle.applyTo(),
                body.get("fields"), body.get("layout"), body.get("pendingConfirmation"), bundle.bundleId(), bundle.bodyHash()));
        record(operator, "FORM_TEMPLATE", target, detail);
        return new DistributionOutcome(tenant, bundle.bundleId(), DistributionOutcome.Result.INSERTED, "inserted");
    }

    /** 선행을 이 번들의 개시일로 닫는다. 이미 같은 날짜로 닫혀 있으면 그대로(재실행), 다른 날짜면 거부. */
    private static void closePredecessor(TenantId tenant, String predecessor, LocalDate currentApplyTo, LocalDate applyFrom,
                                         java.util.function.IntSupplier close) {
        if (currentApplyTo == null) {
            if (close.getAsInt() != 1) {
                throw new GovernanceRejectedException(tenant + ": could not close " + predecessor);
            }
        } else if (!currentApplyTo.equals(applyFrom)) {
            throw new GovernanceRejectedException(tenant + ": " + predecessor + " is already closed on " + currentApplyTo
                    + ", not on " + applyFrom);
        }
    }

    private static ObjectNode detail(Bundle bundle, String outcome) {
        ObjectNode detail = JSON.createObjectNode();
        detail.put("outcome", outcome).put("bundleId", bundle.bundleId()).put("bundleHash", bundle.bodyHash())
                .put("applyFrom", bundle.applyFrom().toString());
        return detail;
    }

    private void record(Operator operator, String targetKind, String targetId, ObjectNode detail) {
        audit.append(new AuditEntry(clock.instant(), operator.subject(), Operator.ROLE, AuditAction.RULE_DISTRIBUTE, targetKind, targetId,
                detail));
    }
}
