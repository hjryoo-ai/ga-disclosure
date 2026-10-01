package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.validation.ValidationRegistry;
import com.ga.disclosure.workflow.catalog.InsurerPanelPort;
import com.ga.disclosure.workflow.catalog.ProductCatalogPort;
import com.ga.disclosure.workflow.catalog.ProductGroup;
import com.ga.platform.core.tenant.TenantId;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 확인서 로드(같은 패키지의 유스케이스들이 함께 쓴다): 행 잠금으로 읽고, 고정된 룰·서식을 고정 ID로 로드해(재해석하지 않는다) 판단 재료와 함께
 * 애그리게이트를 복원한다. 판단 재료 = 고정 서식, 상담일 상품군 이름·패널, 고정 룰의 사유 라벨(서식 결속이 가리키는 사실, 3B).
 */
final class DisclosureLoader {

    record Loaded(Disclosure disclosure, EffectiveRule rule, TemplateResolution template, StageCheck check) {
    }

    private final DisclosureStore store;
    private final TenantProfilePort tenants;
    private final ProductCatalogPort catalog;
    private final InsurerPanelPort panel;
    private final RuleResolver rules;
    private final TemplateResolver templates;
    private final ValidationRegistry registry;

    DisclosureLoader(DisclosureStore store, TenantProfilePort tenants, ProductCatalogPort catalog, InsurerPanelPort panel, RuleResolver rules,
                     TemplateResolver templates, ValidationRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.panel = Objects.requireNonNull(panel, "panel");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    Loaded load(TenantId tenant, DisclosureId id) {
        DisclosureRecord rec = store.loadForUpdate(id).orElseThrow(() -> new DisclosureNotFoundException(id));
        EffectiveRule rule = rules.load(tenant, rec.consultDate(), rec.ruleVersionId(), rec.tenantRuleVersionIdOrNull());
        TemplateResolution template = templates.load(tenant, rec.template());
        Disclosure d = rec.restore(context(tenant, rule, template, rec.groupCode(), rec.consultDate()));
        return new Loaded(d, rule, template, check(rule, template));
    }

    StageCheck check(EffectiveRule rule, TemplateResolution template) {
        return (stage, subject) -> registry.run(stage, subject, rule, template);
    }

    /** 애그리게이트 판단 재료: 고정 서식, 상담일 상품군 이름·패널, 고정 룰의 사유 라벨(서식 결속이 가리키는 사실). */
    DisclosureContext context(TenantId tenant, EffectiveRule rule, TemplateResolution template, GroupCode group, LocalDate consultDate) {
        TenantProfilePort.TenantProfile profile = tenants.profile(tenant);
        String groupName = catalog.listGroups(tenant, consultDate).stream().filter(g -> g.code().equals(group))
                .map(ProductGroup::name).findFirst().orElse(null);
        Map<ReasonCode, String> labels = new LinkedHashMap<>();
        rule.reasonCodes().forEach(r -> labels.put(r.code(), r.label()));
        return new DisclosureContext(profile.largeGa(), panel.lookupFor(tenant), template, groupName, panel.panel(tenant, consultDate), labels);
    }
}
