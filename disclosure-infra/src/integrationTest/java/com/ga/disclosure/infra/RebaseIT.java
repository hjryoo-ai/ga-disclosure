package com.ga.disclosure.infra;

import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.disclosure.workflow.disclosure.SealService.Rejection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B S7(3A 수용심사 §3-8, 승인 Q3): 재기준.
 * <ul>
 *   <li>소급 사규로 봉인이 {@code RULE_SUPERSEDED} 거부·플래그 → 재기준(플래그 없으면 {@code REBASE_NOT_ALLOWED}) → COMPARED, 새 고정 버전,
 *       스냅샷·추천사유 폐기, 플래그 {@code REBASED}.</li>
 *   <li>재기준 전 승인(옛 고정 버전에 귀속)은 남아 있지만 봉인이 인정하지 않는다({@code APPROVAL_MISSING}). 새 승인 뒤 봉인 성공, 오버라이드
 *       플래그는 {@code APPROVED}(해소자 = 승인자)로 닫힌다.</li>
 *   <li>새 룰의 COMPARE 검증을 통과하지 못하면(소급 GLOBAL 룰이 최소 비교 개수를 3 → 4로) DRAFT로 간다 — 막다른 상태가 없다.</li>
 * </ul>
 */
class RebaseIT {

    private static void approveAll(SealSetup s, DisclosureId id) {
        for (ValidationResult r : s.w.service.sealBlockers(Callers.of(s.w.tenant, SealSetup.MANAGER), id)) {
            s.w.service.approveException(Callers.of(s.w.tenant, SealSetup.MANAGER), id, r.ruleId(), r.subjectHash().orElseThrow(), "가입설계서로 확인");
        }
    }

    private static void reason(SealSetup s, DisclosureId id) {
        s.w.service.requestGrades(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id);
        s.w.service.setRecommendations(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id, List.of(new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null),
                new AgentReason(2, List.of(ReasonCode.of("COVERAGE")), null)));
    }

    @Test
    void rebaseRepinsAndOldApprovalsStopCounting() {
        try (SealSetup s = new SealSetup()) {
            DisclosureId id = SealScenarios.reasonedWithTempProduct(s.w, "Q-2026-0101");
            approveAll(s, id);
            assertThat(s.w.service.sealBlockers(Callers.of(s.w.tenant, SealSetup.MANAGER), id)).as("승인으로 다 덮였다").isEmpty();
            DisclosureId other = s.w.reasoned();
            assertThat(s.lifecycle.rebase(Callers.of(s.w.tenant, WorkflowSetup.AGENT), other).rejection())
                    .as("플래그 없는 재기준은 거부").contains(LifecycleService.Rejection.REBASE_NOT_ALLOWED);

            SealScenarios.retroactiveTenantRule(s.w);
            SealService.Outcome refused = s.seal.seal(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id);
            assertThat(refused.rejections()).containsExactly(Rejection.RULE_SUPERSEDED);

            LifecycleService.Outcome rebased = s.lifecycle.rebase(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id);
            assertThat(rebased.applied()).isTrue();
            assertThat(rebased.status()).isEqualTo(DisclosureStatus.COMPARED);
            String t = s.w.tenant.value();
            assertThat(s.text("SELECT rule_version_id || '|' || coalesce(tenant_rule_version_id, '-') || '|' || coalesce(grade_snapshot_id, '-')"
                    + " FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", t, id.value())).isEqualTo("DISC-2026-07|HOUSE-2026|-");
            assertThat(s.count("SELECT count(*) FROM recommendation WHERE tenant_id = ? AND disclosure_id = ?", t, id.value())).isZero();
            assertThat(s.count("SELECT count(*) FROM disclosure_item WHERE tenant_id = ? AND disclosure_id = ? AND grade_status IS NOT NULL",
                    t, id.value())).isZero();
            assertThat(s.text("SELECT resolution FROM compliance_flag WHERE tenant_id = ? AND disclosure_id = ? AND type = 'RULE_SUPERSEDED_DRAFT'",
                    t, id.value())).isEqualTo("REBASED");
            long oldApprovals = s.count("SELECT count(*) FROM review WHERE tenant_id = ? AND disclosure_id = ?", t, id.value());
            assertThat(oldApprovals).as("옛 승인은 지우지 않는다").isEqualTo(2);

            reason(s, id);
            SealService.Outcome stillRefused = s.seal.seal(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id);
            assertThat(stillRefused.rejections()).as("옛 승인은 새 고정 버전에서 효력이 없다").containsExactly(Rejection.APPROVAL_MISSING);

            approveAll(s, id);
            assertThat(s.count("SELECT count(*) FROM review WHERE tenant_id = ? AND disclosure_id = ? AND tenant_rule_version_id = 'HOUSE-2026'",
                    t, id.value())).isEqualTo(2);
            SealService.Outcome sealed = s.seal.seal(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id);
            assertThat(sealed.sealed()).as("%s", sealed.rejections()).isTrue();
            assertThat(s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ? AND disclosure_id = ? AND type = 'VALIDATION_OVERRIDE'"
                    + " AND resolution = 'APPROVED' AND resolved_by = ?", t, id.value(), SealSetup.MANAGER.subject())).isEqualTo(2);
            assertThat(s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ? AND disclosure_id = ? AND resolved_at IS NULL",
                    t, id.value())).isZero();
        }
    }

    @Test
    void rebaseThatNoLongerPassesCompareGoesToDraft() {
        try (SealSetup s = new SealSetup(SealScenarios.without2027Rule())) {
            DisclosureId id = s.w.reasoned();                          // 3사 비교(최소 3 충족)
            SealScenarios.retroactiveGlobalRule(s.w, 4);                // 소급: 최소 4
            assertThat(s.seal.seal(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id).rejections()).containsExactly(Rejection.RULE_SUPERSEDED);
            LifecycleService.Outcome rebased = s.lifecycle.rebase(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id);
            assertThat(rebased.status()).isEqualTo(DisclosureStatus.DRAFT);
            assertThat(rebased.results()).filteredOn(ValidationResult::blocking).extracting(ValidationResult::ruleId).containsExactly("R-MIN-COMPARE");
            assertThat(s.text("SELECT rule_version_id FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", s.w.tenant.value(), id.value()))
                    .isEqualTo("DISC-2026-09");
            // DRAFT에서 항목을 고쳐 다시 진행할 수 있다(막다른 상태가 아니다)
            s.w.service.replaceItems(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id, List.of(WorkflowSetup.catalogItem("INS-A:PRD-1001", true),
                    WorkflowSetup.catalogItem("INS-B:PRD-2044", false), WorkflowSetup.catalogItem("INS-C:PRD-3120", true),
                    WorkflowSetup.catalogItem("INS-E:PRD-5001", false)));
            assertThat(s.w.service.compare(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id).status()).isEqualTo(DisclosureStatus.COMPARED);
        }
    }
}
