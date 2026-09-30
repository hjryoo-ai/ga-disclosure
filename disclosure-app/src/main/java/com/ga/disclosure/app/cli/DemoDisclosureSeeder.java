package com.ga.disclosure.app.cli;

import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.compliance.rules.TenantTransactions;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.customer.RegistrationKey;
import com.ga.disclosure.workflow.disclosure.CommandResult;
import com.ga.disclosure.workflow.disclosure.DisclosureLookup;
import com.ga.disclosure.workflow.disclosure.DisclosureService;
import com.ga.disclosure.workflow.disclosure.ItemInput;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;

import java.io.PrintStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 데모 확인서 흐름(설계서 부록 A-1·A-2, 3A 지시문 §5): 고객(등록 멱등 키로 찾는다) → 초안 → 항목 → 비교 → 산출 → 사유 → REASONED, 그리고
 * 고객 요청 보험사 추가(→ COMPARED → 재산출 → 사유). 전부 운영과 같은 유스케이스를 부른다.
 *
 * <p><b>데모 편의 규칙(운영 동작이 아님):</b> 같은 고객·상담일·상품군의 확인서가 이미 있으면 그 사례를 NOOP으로 건너뛴다 — 시드를 두 번 돌려도
 * 확인서가 늘지 않게 하려는 것뿐이다. 운영에서는 같은 고객·상담일·상품군으로 여러 확인서를 만들 수 있다.
 */
final class DemoDisclosureSeeder {

    private final DisclosureService disclosures;
    private final DisclosureLookup lookup;
    private final CustomerVault customers;
    private final TenantTransactions transactions;
    private final PrintStream out;

    DemoDisclosureSeeder(DisclosureService disclosures, DisclosureLookup lookup, CustomerVault customers, TenantTransactions transactions,
                         PrintStream out) {
        this.disclosures = Objects.requireNonNull(disclosures, "disclosures");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.customers = Objects.requireNonNull(customers, "customers");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.out = Objects.requireNonNull(out, "out");
    }

    void seed(TenantId tenant, Actor agent, String json) {
        JsonNode root = Canonicalizer.parseStrict(json);
        if (root.path("schemaVersion").asInt(-1) != 1) {
            throw new CliFailure("demo disclosures file needs schemaVersion 1");
        }
        String prefix = root.path("customerKeyPrefix").asString();
        for (JsonNode c : root.path("cases")) {
            runCase(tenant, agent, prefix, c);
        }
    }

    private void runCase(TenantId tenant, Actor agent, String prefix, JsonNode c) {
        String id = c.get("id").asString();
        RegistrationKey key = new RegistrationKey(prefix + c.get("customer").asString());
        CustomerRef customer = transactions.inTenant(tenant, () -> customers.findByRegistrationKey(key))
                .orElseThrow(() -> new CliFailure("case " + id + ": customer " + key + " is not registered — run customer import first"));
        GroupCode group = GroupCode.of(c.get("groupCode").asString());
        LocalDate consult = LocalDate.parse(c.get("consultDate").asString());
        List<DisclosureId> existing = transactions.inTenant(tenant, () -> lookup.findFor(customer, consult, group));
        if (!existing.isEmpty()) {
            out.println("DEMO_DISCLOSURE " + tenant + " " + id + " NOOP existing=" + existing.getFirst() + " (demo rule: same customer, date, group)");
            return;
        }
        DisclosureId d = disclosures.createDraft(tenant, agent, customer, group, consult, TemplateType.STANDARD);
        List<ItemInput> items = new ArrayList<>();
        c.get("items").forEach(i -> items.add(item(i)));
        step(id, "replace", disclosures.replaceItems(tenant, agent, d, items));
        step(id, "compare", disclosures.compare(tenant, agent, d));
        step(id, "grade", disclosures.requestGrades(tenant, agent, d));
        CommandResult last = step(id, "reason", disclosures.setRecommendations(tenant, agent, d, reasons(c.get("reasons"))));
        JsonNode request = c.get("customerRequest");
        if (request != null) {
            items.add(item(request.get("add")));
            step(id, "customer-request", disclosures.replaceItems(tenant, agent, d, items));
            step(id, "regrade", disclosures.requestGrades(tenant, agent, d));
            last = step(id, "reason", disclosures.setRecommendations(tenant, agent, d, reasons(request.get("reasons"))));
        }
        out.println("DEMO_DISCLOSURE " + tenant + " " + id + " CREATED id=" + d + " status=" + last.status());
    }

    private CommandResult step(String caseId, String step, CommandResult r) {
        List<String> overridable = r.results().stream().filter(v -> v.overridable()).map(v -> v.ruleId()).toList();
        out.println("  " + caseId + " " + step + " -> " + r.status() + (r.applied() ? "" : " REJECTED " + r.rejectionOrNull())
                + (overridable.isEmpty() ? "" : " overridable=" + overridable));
        if (!r.applied()) {
            throw new CliFailure("case " + caseId + " stopped at " + step + ": " + r.rejectionOrNull() + " "
                    + r.results().stream().filter(v -> v.blocking()).map(v -> v.ruleId()).toList() + " " + r.engineViolations());
        }
        return r;
    }

    private static ItemInput item(JsonNode i) {
        boolean recommended = i.path("recommended").asBoolean(false);
        boolean requested = i.path("requestedByCustomer").asBoolean(false);
        Map<String, JsonNode> values = new LinkedHashMap<>();
        JsonNode v = i.path("values");
        v.propertyNames().forEach(name -> values.put(name, v.get(name)));
        JsonNode temp = i.get("temp");
        if (temp != null) {
            return new ItemInput.Temp(InsurerCode.of(temp.get("insurer").asString()), temp.get("productName").asString(),
                    temp.get("quoteDocNo").asString(), recommended, requested, values);
        }
        return new ItemInput.Catalog(ProductKey.parse(i.get("productKey").asString()), recommended, requested, values);
    }

    private static List<AgentReason> reasons(JsonNode array) {
        List<AgentReason> out = new ArrayList<>();
        for (JsonNode r : array) {
            List<ReasonCode> codes = new ArrayList<>();
            r.get("codes").forEach(code -> codes.add(ReasonCode.of(code.asString())));
            out.add(new AgentReason(r.get("itemNo").asInt(), codes, r.hasNonNull("text") ? r.get("text").asString() : null));
        }
        return out;
    }
}
