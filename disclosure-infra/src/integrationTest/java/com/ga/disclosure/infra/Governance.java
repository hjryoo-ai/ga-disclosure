package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.compliance.rules.Operator;
import com.ga.disclosure.compliance.rules.RuleActivationJob;
import com.ga.disclosure.compliance.rules.RuleApprovalService;
import com.ga.disclosure.compliance.rules.RuleBundleReconciler;
import com.ga.disclosure.compliance.rules.RuleDistributionService;
import com.ga.disclosure.infra.persistence.AuditLogRepository;
import com.ga.disclosure.infra.persistence.ComplianceFlagRepository;
import com.ga.disclosure.infra.persistence.FormTemplateRepository;
import com.ga.disclosure.infra.persistence.RuleVersionRepository;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.infra.tx.TenantTransactionTemplate;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantSessionBinder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 통합 테스트용 조립: 실제 어댑터(disclosure_app 데이터소스, TenantSessionBinder 트랜잭션)와 룰 거버넌스 서비스.
 * 시계는 고정(Asia/Seoul) — 서비스는 주입된 {@link Clock}만 쓴다.
 */
final class Governance {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Operator OPERATOR = new Operator("ops@test");

    final PostgresHarness db = PostgresHarness.get();
    final TenantJdbcGateway gateway = new TenantJdbcGateway(db.appDataSource());
    final RuleVersionRepository rules = new RuleVersionRepository(gateway);
    final FormTemplateRepository templates = new FormTemplateRepository(gateway);
    final AuditLogRepository audit = new AuditLogRepository(gateway);
    final ComplianceFlagRepository flags = new ComplianceFlagRepository(gateway);
    final TenantTransactionTemplate tx = new TenantTransactionTemplate(new TenantSessionBinder(db.appDataSource()));
    final Clock clock;
    final RuleDistributionService distribution;
    final RuleApprovalService approval;
    final RuleActivationJob activation;
    final RuleBundleReconciler reconciler;
    final RuleResolver resolver = new RuleResolver(rules);
    final TemplateResolver templateResolver = new TemplateResolver(templates);

    Governance(String instant) {
        this.clock = Clock.fixed(Instant.parse(instant), SEOUL);
        this.distribution = new RuleDistributionService(rules, templates, audit, tx, clock);
        this.approval = new RuleApprovalService(rules, audit, tx, clock);
        this.activation = new RuleActivationJob(rules, audit, tx, clock);
        this.reconciler = new RuleBundleReconciler(rules, templates, flags, audit, tx, clock);
    }

    Governance() {
        this("2026-06-30T00:00:00Z");
    }

    TenantId freshTenant(String prefix) {
        String t = SeedData.uniqueTenant(prefix);
        db.seed(t, c -> SeedData.tenant(c, t));
        return TenantId.of(t);
    }

    <T> T in(TenantId tenant, Supplier<T> work) {
        return tx.inTenant(tenant, work);
    }

    List<AuditRecord> auditOf(TenantId tenant) {
        return in(tenant, audit::readAll);
    }

    List<UUID> openDriftFlags(TenantId tenant) {
        return in(tenant, () -> flags.findOpen(RuleBundleReconciler.FLAG_TYPE));
    }
}
