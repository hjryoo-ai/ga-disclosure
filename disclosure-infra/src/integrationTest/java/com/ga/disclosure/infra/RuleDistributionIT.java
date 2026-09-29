package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.compliance.rules.DistributionOutcome;
import com.ga.disclosure.compliance.rules.GovernanceRejectedException;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.rules.bundle.Bundle;
import com.ga.disclosure.rules.bundle.RuleBundle;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantDirectoryReader;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 C5: 번들 배포 멱등(같은 번들 재실행은 no-op이고 감사 행도 NOOP 1건), 같은 ID·다른 해시 거부, supersedes 대상 없음 거부.
 * 테넌트마다 한 트랜잭션 — 거부된 테넌트에는 아무것도 남지 않고 다른 테넌트의 결과는 유지된다.
 */
class RuleDistributionIT {

    private final Governance g = new Governance();

    private static final RuleBundle Y2026 = Bundles.rule(Bundles.DISC_2026_07);
    private static final RuleBundle Y2027 = Bundles.rule(Bundles.DISC_2027_01);

    private List<String> outcomes(TenantId tenant) {
        return g.auditOf(tenant).stream().map(r -> r.entry().action() + ":" + r.entry().detail().path("outcome").asString()).toList();
    }

    @Test
    void firstDistributionInsertsAnApprovedGlobalReplica() {
        TenantId t = g.freshTenant("DST");
        DistributionOutcome out = g.distribution.distribute(Y2026, t, Governance.OPERATOR);
        assertThat(out.result()).isEqualTo(DistributionOutcome.Result.INSERTED);
        RuleVersion stored = g.in(t, () -> g.rules.find(Y2026.ruleVersionId())).orElseThrow();
        assertThat(stored.scope()).isEqualTo(RuleScope.GLOBAL);
        assertThat(stored.status()).isEqualTo(RuleStatus.APPROVED);
        assertThat(stored.sourceBundleId()).isEqualTo(Y2026.bundleId());
        assertThat(stored.bundleHash()).isEqualTo(Y2026.bodyHash());
        assertThat(stored.approvedBy()).isEqualTo(Governance.OPERATOR.subject());
        AuditRecord row = g.auditOf(t).getFirst();
        assertThat(row.entry().actorRole()).isEqualTo("OPERATOR");
        assertThat(row.entry().targetId()).isEqualTo("DISC-2026-07");
    }

    @Test
    void rerunningTheSameBundleIsANoopWithOneNoopAuditRow() {
        TenantId t = g.freshTenant("DST");
        g.distribution.distribute(Y2026, t, Governance.OPERATOR);
        DistributionOutcome again = g.distribution.distribute(Y2026, t, Governance.OPERATOR);
        assertThat(again.result()).isEqualTo(DistributionOutcome.Result.NOOP);
        assertThat(outcomes(t)).containsExactly("RULE_DISTRIBUTE:INSERTED", "RULE_DISTRIBUTE:NOOP");
    }

    @Test
    void sameIdWithADifferentHashIsRejectedAndLeavesNothing() {
        TenantId t = g.freshTenant("DST");
        g.distribution.distribute(Y2026, t, Governance.OPERATOR);
        Bundle changed = BundleFiles.editedCanonical(Bundles.DISC_2026_07, body -> body.put("snapshotMaxAgeDays", 3));
        assertThat(changed.bodyHash()).isNotEqualTo(Y2026.bodyHash());
        assertThatThrownBy(() -> g.distribution.distribute(changed, t, Governance.OPERATOR))
                .isInstanceOf(GovernanceRejectedException.class).hasMessageContaining("needs a new rule_version_id");
        assertThat(g.in(t, () -> g.rules.find(Y2026.ruleVersionId())).orElseThrow().bundleHash()).isEqualTo(Y2026.bodyHash());
        assertThat(outcomes(t)).containsExactly("RULE_DISTRIBUTE:INSERTED");
    }

    @Test
    void supersedesWithoutPredecessorIsRejectedAndLeavesNothing() {
        TenantId t = g.freshTenant("DST");
        assertThatThrownBy(() -> g.distribution.distribute(Y2027, t, Governance.OPERATOR))
                .isInstanceOf(GovernanceRejectedException.class).hasMessageContaining("does not exist");
        assertThat(g.in(t, () -> g.rules.find(Y2027.ruleVersionId()))).isEmpty();
        assertThat(g.auditOf(t)).isEmpty();
    }

    @Test
    void supersedesClosesThePredecessorInTheSameTransaction() {
        TenantId t = g.freshTenant("DST");
        g.distribution.distribute(Y2026, t, Governance.OPERATOR);
        g.distribution.distribute(Y2027, t, Governance.OPERATOR);
        assertThat(g.in(t, () -> g.rules.find(Y2026.ruleVersionId())).orElseThrow().applyTo()).isEqualTo(LocalDate.parse("2027-01-01"));
        AuditRecord row = g.auditOf(t).getLast();
        assertThat(row.entry().detail().path("supersedes").asString()).isEqualTo("DISC-2026-07");
        // 재실행: 선행은 이미 같은 날짜로 닫혀 있으므로 그대로, 자신은 no-op
        assertThat(g.distribution.distribute(Y2027, t, Governance.OPERATOR).result()).isEqualTo(DistributionOutcome.Result.NOOP);
    }

    @Test
    void predecessorClosedOnAnotherDateIsRejected() {
        TenantId t = g.freshTenant("DST");
        g.distribution.distribute(Y2026, t, Governance.OPERATOR);
        g.in(t, () -> g.rules.closeApplyTo(RuleVersionId.of("DISC-2026-07"), LocalDate.parse("2026-12-01")));
        assertThatThrownBy(() -> g.distribution.distribute(Y2027, t, Governance.OPERATOR))
                .isInstanceOf(GovernanceRejectedException.class).hasMessageContaining("already closed on 2026-12-01");
    }

    @Test
    void eachTenantIsItsOwnTransaction() {
        TenantId ready = g.freshTenant("DST");
        TenantId bare = g.freshTenant("DST");
        g.distribution.distribute(Y2026, ready, Governance.OPERATOR);
        List<DistributionOutcome> out = g.distribution.distribute(Y2027, List.of(ready, bare), Governance.OPERATOR);
        assertThat(out).extracting(DistributionOutcome::result)
                .containsExactly(DistributionOutcome.Result.INSERTED, DistributionOutcome.Result.REJECTED);
        assertThat(g.in(ready, () -> g.rules.find(Y2027.ruleVersionId()))).isPresent();
        assertThat(g.in(bare, () -> g.rules.find(Y2027.ruleVersionId()))).isEmpty();
    }

    @Test
    void templateBundleIsDistributedIdempotently() {
        TenantId t = g.freshTenant("DST");
        Bundle standard = Bundles.load(Bundles.STANDARD_V1);
        assertThat(g.distribution.distribute(standard, t, Governance.OPERATOR).result()).isEqualTo(DistributionOutcome.Result.INSERTED);
        assertThat(g.distribution.distribute(standard, t, Governance.OPERATOR).result()).isEqualTo(DistributionOutcome.Result.NOOP);
        var stored = g.in(t, () -> g.templates.find(TemplateRef.of("STANDARD", 1))).orElseThrow();
        assertThat(stored.bundleHash()).isEqualTo(standard.bodyHash());
        assertThat(stored.pendingConfirmation().get(0).path("ref").asString()).isEqualTo("TODO(confirm#2)");
        assertThat(g.auditOf(t)).extracting(r -> r.entry().action()).containsOnly(AuditAction.RULE_DISTRIBUTE);
    }

    @Test
    void tenantDirectoryListsEveryTenantThroughTheOperatorRole() {
        TenantId a = g.freshTenant("DIR");
        TenantId b = g.freshTenant("DIR");
        PostgresHarness db = PostgresHarness.get();
        List<TenantId> all = new TenantDirectoryReader(db.jdbcUrl(), PostgresHarness.OPERATOR, PostgresHarness.OPERATOR_PASSWORD).allTenants();
        assertThat(all).contains(a, b);
    }
}
