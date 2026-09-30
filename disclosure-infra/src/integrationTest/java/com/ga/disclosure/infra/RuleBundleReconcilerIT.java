package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.compliance.rules.ReconcileReport;
import com.ga.disclosure.rules.bundle.Bundle;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 C7: 번들 대사. 정상이면 RULE_DRIFT 0건, 복제본 본문을 disclosure_migrator가 트리거를 끄고 변조하면(설계서 부록 A-5의
 * "트리거 우회 가정") 대사가 RULE_DRIFT 플래그를 만든다 — 본문과 bundle_hash를 함께 맞춰 고쳐도 번들 파일과의 대조가 잡는다.
 */
class RuleBundleReconcilerIT {

    private final Governance g = new Governance();
    private final List<Bundle> canonical = List.of(Bundles.load(Bundles.DISC_2026_07), Bundles.load(Bundles.DISC_2027_01),
            Bundles.load(Bundles.STANDARD_V1));

    private TenantId distributed() {
        TenantId t = g.freshTenant("REC");
        canonical.forEach(b -> g.distribution.distribute(b, t, Governance.OPERATOR));
        return t;
    }

    private void tamper(TenantId t, String table, String trigger, String set, String where) {
        g.db.seed(t.value(), c -> {
            SeedData.exec(c, "ALTER TABLE " + table + " DISABLE TRIGGER " + trigger);
            SeedData.exec(c, "UPDATE " + table + " SET " + set + " WHERE tenant_id = ? AND " + where, t.value());
            SeedData.exec(c, "ALTER TABLE " + table + " ENABLE TRIGGER " + trigger);
        });
    }

    @Test
    void untouchedReplicasProduceNoDrift() {
        TenantId t = distributed();
        ReconcileReport report = g.reconciler.reconcile(t, canonical, Governance.OPERATOR);
        assertThat(report.checked()).isEqualTo(3);
        assertThat(report.drifts()).isEmpty();
        assertThat(g.openDriftFlags(t)).isEmpty();
        AuditRecord row = g.auditOf(t).getLast();
        assertThat(row.entry().action()).isEqualTo(AuditAction.RULE_RECONCILE);
        assertThat(row.entry().detail().path("drift").asInt()).isZero();
    }

    @Test
    void bodyTamperedByTheOwnerIsFlagged() {
        TenantId t = distributed();
        tamper(t, "rule_version", "trg_rule_version_guard_update", "body = jsonb_set(body, '{minCompare}', '2')",
                "rule_version_id = 'DISC-2026-07'");
        ReconcileReport report = g.reconciler.reconcile(t, canonical, Governance.OPERATOR);
        assertThat(report.drifts()).singleElement().satisfies(d -> {
            assertThat(d.targetId()).isEqualTo("DISC-2026-07");
            assertThat(d.problems()).anyMatch(p -> p.contains("stored bundle_hash"));
        });
        assertThat(g.openDriftFlags(t)).containsExactly(report.drifts().getFirst().flagId());
        assertThat(g.auditOf(t).getLast().entry().detail().toString()).contains(report.drifts().getFirst().flagId().toString());

        // Phase 2: 다음 날 대사가 같은 드리프트를 다시 찾아도 열린 플래그는 하나 — 같은 ID를 보고하고 FLAG_RAISE 감사도 한 번뿐
        ReconcileReport again = g.reconciler.reconcile(t, canonical, Governance.OPERATOR);
        assertThat(again.drifts()).singleElement().satisfies(d -> assertThat(d.flagId()).isEqualTo(report.drifts().getFirst().flagId()));
        assertThat(g.openDriftFlags(t)).hasSize(1);
        assertThat(g.auditOf(t).stream().filter(r -> r.entry().action() == com.ga.disclosure.audit.AuditAction.FLAG_RAISE).count())
                .isEqualTo(1);
    }

    @Test
    void bodyAndHashTamperedTogetherAreStillCaughtAgainstTheBundleFile() {
        TenantId t = distributed();
        tamper(t, "rule_version", "trg_rule_version_guard_update",
                "body = jsonb_set(body, '{minCompare}', '2'), bundle_hash = encode(sha256(convert_to('x', 'UTF8')), 'hex')",
                "rule_version_id = 'DISC-2027-01'");
        // 공격자가 hash 컬럼까지 맞춰 고치는 경우를 흉내 낸다: 저장 해시를 변조 본문의 정규화 해시로 바꾼다.
        String forged = g.in(t, () -> com.ga.platform.canonical.Sha256.of(com.ga.platform.canonical.Canonicalizer.canonicalize(
                g.rules.find(com.ga.disclosure.domain.vo.RuleVersionId.of("DISC-2027-01")).orElseThrow().body())));
        tamper(t, "rule_version", "trg_rule_version_guard_update", "bundle_hash = '" + forged + "'", "rule_version_id = 'DISC-2027-01'");
        ReconcileReport report = g.reconciler.reconcile(t, canonical, Governance.OPERATOR);
        assertThat(report.drifts()).singleElement().satisfies(d -> {
            assertThat(d.targetId()).isEqualTo("DISC-2027-01");
            assertThat(d.problems()).singleElement().asString().contains("body differs from bundle file");
        });
    }

    @Test
    void templateTamperIsFlaggedToo() {
        TenantId t = distributed();
        tamper(t, "form_template", "trg_form_template_guard_update", "fields = fields - 0", "template_id = 'STANDARD'");
        ReconcileReport report = g.reconciler.reconcile(t, canonical, Governance.OPERATOR);
        assertThat(report.drifts()).singleElement().satisfies(d -> assertThat(d.targetId()).isEqualTo("STANDARD.v1"));
    }

    @Test
    void replicaOfAnUnknownBundleIsFlagged() {
        TenantId t = distributed();
        ReconcileReport report = g.reconciler.reconcile(t, List.of(Bundles.load(Bundles.DISC_2026_07), Bundles.load(Bundles.STANDARD_V1)),
                Governance.OPERATOR);
        assertThat(report.drifts()).singleElement().satisfies(d ->
                assertThat(d.problems()).anyMatch(p -> p.contains("not among the canonical bundle files")));
    }
}
