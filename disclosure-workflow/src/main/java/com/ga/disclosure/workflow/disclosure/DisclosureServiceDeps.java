package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.validation.ValidationRegistry;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.catalog.InsurerPanelPort;
import com.ga.disclosure.workflow.catalog.ProductCatalogPort;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.identity.AgentDirectory;

import java.time.Clock;
import java.util.Objects;

/** 확인서 유스케이스들(봉인·정정·무효·재기준)이 함께 쓰는 포트 묶음. 조립은 앱 구성이 한다. */
public record DisclosureServiceDeps(DisclosureStore store, ReviewStore reviews, DisclosureFlagPort flags, TenantProfilePort tenants,
                                    ProductCatalogPort catalog, InsurerPanelPort panel, CustomerVault customers, RuleResolver rules,
                                    TemplateResolver templates, ValidationRegistry registry, AuditPort audit, WorkflowTransactions transactions,
                                    Clock clock, AgentDirectory agents, OutboxPort outbox, AuthorizationPort authz) {

    public DisclosureServiceDeps {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(reviews, "reviews");
        Objects.requireNonNull(flags, "flags");
        Objects.requireNonNull(tenants, "tenants");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(panel, "panel");
        Objects.requireNonNull(customers, "customers");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(templates, "templates");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(agents, "agents");
        Objects.requireNonNull(outbox, "outbox");
        Objects.requireNonNull(authz, "authz");
    }

    DisclosureLoader loader() {
        return new DisclosureLoader(store, tenants, catalog, panel, rules, templates, registry);
    }
}
