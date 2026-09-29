package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.compliance.rules.ActivationReport;
import com.ga.disclosure.compliance.rules.GovernanceRejectedException;
import com.ga.disclosure.domain.enums.ManagerConfirmMode;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.bundle.RuleBundle;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 C6: supersedes 배포가 선행 룰을 닫고, 활성화 배치가 경계일에 RETIRED → ACTIVE 순서로 전이한다(주입된 Clock:
 * 2026-12-31·2027-01-01). 사규 승인(허용 키만)과 사규 활성화도 같은 배치가 처리한다.
 */
class RuleActivationIT {

    private static final RuleBundle Y2026 = Bundles.rule(Bundles.DISC_2026_07);
    private static final RuleBundle Y2027 = Bundles.rule(Bundles.DISC_2027_01);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Governance setup = new Governance("2026-06-30T00:00:00Z");

    private TenantId distributedTenant() {
        TenantId t = setup.freshTenant("ACT");
        setup.distribution.distribute(Y2026, t, Governance.OPERATOR);
        setup.distribution.distribute(Y2027, t, Governance.OPERATOR);
        return t;
    }

    /** {@code date} 00:30(Asia/Seoul)에 도는 배치 — 날짜는 주입된 시계에서만 온다. */
    private static Governance at(String date) {
        return new Governance(LocalDate.parse(date).atTime(0, 30).atZone(Governance.SEOUL).toInstant().toString());
    }

    private RuleStatus status(TenantId t, String id) {
        return setup.in(t, () -> setup.rules.find(RuleVersionId.of(id))).orElseThrow().status();
    }

    @Test
    void boundaryDayRetiresThePredecessorBeforeActivatingItsSuccessor() {
        TenantId t = distributedTenant();
        ActivationReport july = at("2026-09-23").activation.run(t, Governance.OPERATOR);
        assertThat(july.activated()).extracting(RuleVersionId::value).containsExactly("DISC-2026-07");
        assertThat(july.retired()).isEmpty();

        ActivationReport eve = at("2026-12-31").activation.run(t, Governance.OPERATOR);
        assertThat(eve.asOf()).isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(eve.activated()).isEmpty();
        assertThat(eve.retired()).isEmpty();
        assertThat(status(t, "DISC-2026-07")).isEqualTo(RuleStatus.ACTIVE);
        assertThat(status(t, "DISC-2027-01")).isEqualTo(RuleStatus.APPROVED);

        ActivationReport newYear = at("2027-01-01").activation.run(t, Governance.OPERATOR);
        assertThat(newYear.retired()).extracting(RuleVersionId::value).containsExactly("DISC-2026-07");
        assertThat(newYear.activated()).extracting(RuleVersionId::value).containsExactly("DISC-2027-01");
        assertThat(status(t, "DISC-2026-07")).isEqualTo(RuleStatus.RETIRED);
        assertThat(status(t, "DISC-2027-01")).isEqualTo(RuleStatus.ACTIVE);

        // 감사 순서: RETIRE가 ACTIVATE보다 먼저(같은 트랜잭션)
        assertThat(setup.auditOf(t).stream().map(r -> r.entry().action()).filter(a -> a != AuditAction.RULE_DISTRIBUTE).toList())
                .containsExactly(AuditAction.RULE_ACTIVATE, AuditAction.RULE_RETIRE, AuditAction.RULE_ACTIVATE);

        // 경계 이후에도 과거 상담일은 과거 룰로 해석된다(RETIRED 포함)
        EffectiveRule before = setup.in(t, () -> setup.resolver.resolve(t, LocalDate.parse("2026-12-31")));
        EffectiveRule after = setup.in(t, () -> setup.resolver.resolve(t, LocalDate.parse("2027-01-01")));
        assertThat(before.minCompare()).isEqualTo(3);
        assertThat(before.managerConfirmMode()).isEqualTo(ManagerConfirmMode.REQUIRED);
        assertThat(after.minCompare()).isEqualTo(4);
        assertThat(after.managerConfirmMode()).isEqualTo(ManagerConfirmMode.OFF);
    }

    @Test
    void runningTheBatchAgainOnTheSameDayChangesNothing() {
        TenantId t = distributedTenant();
        at("2027-01-01").activation.run(t, Governance.OPERATOR);
        ActivationReport again = at("2027-01-01").activation.run(t, Governance.OPERATOR);
        assertThat(again.activated()).isEmpty();
        assertThat(again.retired()).isEmpty();
    }

    @Test
    void approvedRuleWhoseWindowAlreadyEndedIsReportedNotActivated() {
        TenantId t = distributedTenant();
        // 2027-01-01 경계를 놓치고 2027-01-05에 처음 돌면: 2026-07은 활성화하지 않고(구간 종료), 2027-01만 활성화
        ActivationReport late = at("2027-01-05").activation.run(t, Governance.OPERATOR);
        assertThat(late.expiredUnactivated()).extracting(RuleVersionId::value).containsExactly("DISC-2026-07");
        assertThat(late.activated()).extracting(RuleVersionId::value).containsExactly("DISC-2027-01");
        assertThat(status(t, "DISC-2026-07")).isEqualTo(RuleStatus.APPROVED);
    }

    @Test
    void houseRuleIsApprovedOnlyWithOpenKeysThenActivated() {
        TenantId t = distributedTenant();
        String house = """
                {"signDeadlineDays": 10,
                 "channels": {"TOUCH_PAD": true, "REMOTE_LINK": true, "PAPER_SCAN": false, "CERTIFIED_ESIGN": false}}
                """;
        setup.in(t, () -> {
            setup.rules.insert(new RuleVersion(RuleVersionId.of("HOUSE-2026"), RuleScope.TENANT, LocalDate.parse("2026-07-01"), null,
                    RuleStatus.DRAFT, null, null, JSON.readTree(house), null, null));
            setup.rules.insert(new RuleVersion(RuleVersionId.of("HOUSE-LOOSE"), RuleScope.TENANT, LocalDate.parse("2026-07-01"), null,
                    RuleStatus.DRAFT, null, null, JSON.readTree("{\"minCompare\": 2}"), null, null));
            return null;
        });
        assertThatThrownBy(() -> setup.approval.approve(t, RuleVersionId.of("HOUSE-LOOSE"), Governance.OPERATOR))
                .isInstanceOf(GovernanceRejectedException.class).hasMessageContaining("DISALLOWED_OVERRIDE");
        assertThat(status(t, "HOUSE-LOOSE")).isEqualTo(RuleStatus.DRAFT);

        setup.approval.approve(t, RuleVersionId.of("HOUSE-2026"), Governance.OPERATOR);
        RuleVersion approved = setup.in(t, () -> setup.rules.find(RuleVersionId.of("HOUSE-2026"))).orElseThrow();
        assertThat(approved.status()).isEqualTo(RuleStatus.APPROVED);
        assertThat(approved.approvedAt()).isEqualTo(Instant.parse("2026-06-30T00:00:00Z"));
        assertThat(setup.auditOf(t).getLast().entry().detail().get("checkedAgainst").toString())
                .contains("DISC-2026-07", "DISC-2027-01");

        at("2026-09-23").activation.run(t, Governance.OPERATOR);
        EffectiveRule rule = setup.in(t, () -> setup.resolver.resolve(t, LocalDate.parse("2026-09-23")));
        assertThat(rule.tenantRuleVersion()).hasValueSatisfying(id -> assertThat(id.value()).isEqualTo("HOUSE-2026"));
        assertThat(rule.signDeadlineDays()).isEqualTo(10);
        assertThat(rule.channels()).containsEntry(SignatureChannel.PAPER_SCAN, false);
        assertThat(rule.minCompare()).isEqualTo(3);
    }
}
