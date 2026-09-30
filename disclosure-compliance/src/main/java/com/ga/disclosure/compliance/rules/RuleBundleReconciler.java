package com.ga.disclosure.compliance.rules;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.rules.bundle.Bundle;
import com.ga.disclosure.rules.template.FormTemplate;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 번들 대사(준법 배치, 설계서 §5): 테넌트의 번들 복제본(GLOBAL 룰·번들 서식)마다 {@code SHA-256(JCS(body))}를 DB 본문에서 다시
 * 계산해 ① 저장된 {@code bundle_hash} ② 번들 파일의 해시와 대조한다. 불일치는 {@code compliance_flag(type=RULE_DRIFT)}를 올리고,
 * 실행 자체는 감사 기록({@code RULE_RECONCILE}, 드리프트 대상과 플래그 ID 포함)으로 남는다. 정상이면 플래그 0건.
 * 같은 대상의 열린 드리프트 플래그가 이미 있으면 새로 올리지 않고 그 플래그를 보고한다(Phase 2 — 재실행이 플래그를 복제하지 않음).
 */
public final class RuleBundleReconciler {

    public static final String FLAG_TYPE = "RULE_DRIFT";
    public static final String FLAG_SEVERITY = "HIGH";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RuleVersionStore rules;
    private final FormTemplateStore templates;
    private final ComplianceFlagPort flags;
    private final AuditPort audit;
    private final TenantTransactions transactions;
    private final Clock clock;

    public RuleBundleReconciler(RuleVersionStore rules, FormTemplateStore templates, ComplianceFlagPort flags, AuditPort audit,
                                TenantTransactions transactions, Clock clock) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReconcileReport reconcile(TenantId tenant, Collection<? extends Bundle> knownBundles, Operator operator) {
        Map<String, Bundle> byId = knownBundles.stream().collect(Collectors.toMap(Bundle::bundleId, Function.identity(), (a, b) -> a));
        return transactions.inTenant(tenant, () -> {
            List<ReconcileReport.Drift> drifts = new ArrayList<>();
            int checked = 0;
            for (RuleVersion r : rules.findGlobalReplicas()) {
                checked++;
                check("RULE_VERSION", r.id().value(), r.body(), r.bundleHash(), r.sourceBundleId(), byId, drifts, operator);
            }
            for (FormTemplate t : templates.findBundled()) {
                checked++;
                check("FORM_TEMPLATE", t.ref().templateId() + ".v" + t.ref().version(), t.body(), t.bundleHash(), t.sourceBundleId(),
                        byId, drifts, operator);
            }
            ObjectNode detail = JSON.createObjectNode();
            detail.put("checked", checked).put("drift", drifts.size());
            ArrayNode items = detail.putArray("drifts");
            for (ReconcileReport.Drift d : drifts) {
                ObjectNode item = items.addObject();
                item.put("targetKind", d.targetKind()).put("targetId", d.targetId()).put("flagId", d.flagId().toString());
                d.problems().forEach(item.putArray("problems")::add);
            }
            audit.append(new AuditEntry(clock.instant(), operator.subject(), Operator.ROLE, AuditAction.RULE_RECONCILE, "TENANT",
                    tenant.value(), detail));
            return new ReconcileReport(tenant, checked, drifts);
        });
    }

    private void check(String kind, String id, JsonNode body, String storedHash, String bundleId, Map<String, Bundle> byId,
                       List<ReconcileReport.Drift> drifts, Operator operator) {
        List<String> problems = new ArrayList<>();
        String recomputed = Sha256.of(Canonicalizer.canonicalize(body));
        if (!recomputed.equals(storedHash)) {
            problems.add("stored bundle_hash " + storedHash + " != SHA-256(JCS(body)) " + recomputed);
        }
        Bundle bundle = byId.get(bundleId);
        if (bundle == null) {
            problems.add("bundle " + bundleId + " is not among the canonical bundle files");
        } else if (!bundle.bodyHash().equals(recomputed)) {
            problems.add("body differs from bundle file " + bundleId + " (" + bundle.bodyHash() + ")");
        } else if (!bundle.bodyHash().equals(storedHash)) {
            problems.add("stored bundle_hash differs from bundle file " + bundleId);
        }
        if (!problems.isEmpty()) {
            ComplianceFlagPort.RaisedFlag flag = flags.raiseOpen(FLAG_TYPE, FLAG_SEVERITY, kind, id, clock.instant());
            if (flag.created()) {
                ObjectNode detail = JSON.createObjectNode().put("type", FLAG_TYPE).put("severity", FLAG_SEVERITY)
                        .put("flagId", flag.flagId().toString());
                audit.append(new AuditEntry(clock.instant(), operator.subject(), Operator.ROLE, AuditAction.FLAG_RAISE, kind, id, detail));
            }
            drifts.add(new ReconcileReport.Drift(kind, id, flag.flagId(), problems));
        }
    }
}
