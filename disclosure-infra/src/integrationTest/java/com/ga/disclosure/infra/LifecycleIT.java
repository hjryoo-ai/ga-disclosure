package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.disclosure.IllegalTransition;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.workflow.artifact.ObjectLockedException;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.LifecycleReason;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import com.ga.disclosure.workflow.disclosure.SealService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3B S11: 무효·정정의 전이와 부수 효과(상태표 §6.1, 3A 수용심사 §3-7).
 * <ul>
 *   <li>VOID: 가변 상태 4종은 설계사가 무효화(번호 없음), 봉인 후는 {@code exceptionApproval.role}만 — 번호·산출물·잠금은 그대로.</li>
 *   <li>SUPERSEDE: 봉인 이후 상태만, 역할 규칙은 VOID와 같다. 원본 SUPERSEDED + 후속 ID, 새 버전은 버전 + 1·원본 ID·항목 입력 복제(등급·사유 없음)이고
 *       룰·서식은 <b>원본 상담일 재해석</b>(원본 고정 뒤 소급 배포된 사규를 새 버전이 쓴다). 새 버전은 다시 REASONED·봉인까지 간다.</li>
 *   <li>플래그 해소 규칙: 무효·정정은 VALIDATION_OVERRIDE·RULE_SUPERSEDED_DRAFT를 SUPERSEDED_BY_DOCUMENT_STATE로 닫고 GRADE_INCONSISTENT는 남긴다,
 *       봉인 때 더는 실패하지 않는 규칙의 플래그는 RESOLVED_AT_SEAL(SYSTEM). (APPROVED·REBASED는 RebaseIT.)</li>
 * </ul>
 */
class LifecycleIT {

    private final SealSetup s = new SealSetup();

    @AfterEach
    void close() {
        s.close();
    }

    private DisclosureId in(DisclosureStatus status) {
        DisclosureId id = s.w.draft();
        if (status == DisclosureStatus.DRAFT) {
            return id;
        }
        s.w.service.replaceItems(s.w.tenant, WorkflowSetup.AGENT, id, WorkflowSetup.threeItems());
        s.w.service.compare(s.w.tenant, WorkflowSetup.AGENT, id);
        if (status == DisclosureStatus.COMPARED) {
            return id;
        }
        s.w.service.requestGrades(s.w.tenant, WorkflowSetup.AGENT, id);
        if (status == DisclosureStatus.GRADED) {
            return id;
        }
        s.w.service.setRecommendations(s.w.tenant, WorkflowSetup.AGENT, id, List.of(new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null),
                new AgentReason(3, List.of(ReasonCode.of("COVERAGE")), null)));
        return id;
    }

    private String flagResolution(DisclosureId id, String type) {
        return s.text("SELECT coalesce(resolution, 'OPEN') FROM compliance_flag WHERE tenant_id = ? AND disclosure_id = ? AND type = ?",
                s.w.tenant.value(), id.value(), type);
    }

    @ParameterizedTest
    @EnumSource(value = DisclosureStatus.class, names = {"DRAFT", "COMPARED", "GRADED", "REASONED"})
    void agentVoidsAMutableDisclosure(DisclosureStatus status) {
        DisclosureId id = in(status);
        LifecycleService.Outcome o = s.lifecycle.voidDisclosure(s.w.tenant, WorkflowSetup.AGENT, id, new LifecycleReason("OTHER", "고객이 상담을 철회함"));
        assertThat(o.applied()).isTrue();
        assertThat(o.status()).isEqualTo(DisclosureStatus.VOID);
        assertThat(s.text("SELECT status || '|' || coalesce(disclosure_no, '-') || '|' || (voided_at IS NOT NULL)::text FROM disclosure"
                + " WHERE tenant_id = ? AND disclosure_id = ?", s.w.tenant.value(), id.value())).isEqualTo("VOID|-|true");
        assertThat(s.actionsFor(id).getLast()).isEqualTo(AuditAction.DISCLOSURE_VOID);
        assertThat(s.audit().getLast().entry().detail().toString()).doesNotContain("철회").contains("\"reasonCode\":\"OTHER\"", "reasonTextLength");
        assertThat(s.text("SELECT void_reason_code || '|' || void_reason_text FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?",
                s.w.tenant.value(), id.value())).isEqualTo("OTHER|고객이 상담을 철회함");
        assertThatThrownBy(() -> s.lifecycle.voidDisclosure(s.w.tenant, WorkflowSetup.AGENT, id, new LifecycleReason("DUPLICATE", null))).isInstanceOf(IllegalTransition.class);
    }

    @Test
    void voidClosesOverrideFlagsButKeepsEngineSignals() {
        DisclosureId id = SealScenarios.reasonedWithTempProduct(s.w, "Q-2026-0201");
        s.w.in(() -> s.w.flags.raise(DisclosureFlagPort.Type.GRADE_INCONSISTENT, "HIGH", id, "DISCLOSURE", id.toString(), s.w.clock.instant()));
        assertThat(flagResolution(id, "VALIDATION_OVERRIDE")).isEqualTo("OPEN");
        s.lifecycle.voidDisclosure(s.w.tenant, WorkflowSetup.AGENT, id, new LifecycleReason("WRITTEN_IN_ERROR", null));
        assertThat(s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ? AND disclosure_id = ? AND type = 'VALIDATION_OVERRIDE'"
                + " AND resolution = 'SUPERSEDED_BY_DOCUMENT_STATE'", s.w.tenant.value(), id.value())).isEqualTo(2);
        assertThat(flagResolution(id, "GRADE_INCONSISTENT")).isEqualTo("OPEN");
    }

    @Test
    void voidAfterSealingNeedsTheExceptionApprovalRoleAndKeepsTheSeal() {
        SealService.Outcome sealed = s.sealReasoned();
        DisclosureId id = sealed.id();
        LifecycleService.Outcome refused = s.lifecycle.voidDisclosure(s.w.tenant, WorkflowSetup.AGENT, id, new LifecycleReason("CUSTOMER_CANCELLED", null));
        assertThat(refused.rejection()).contains(LifecycleService.Rejection.ROLE_REQUIRED);
        assertThat(refused.status()).isEqualTo(DisclosureStatus.SEALED);
        assertThat(s.actionsFor(id).getLast()).isEqualTo(AuditAction.DISCLOSURE_REJECT);

        LifecycleService.Outcome voided = s.lifecycle.voidDisclosure(s.w.tenant, SealSetup.MANAGER, id, new LifecycleReason("CUSTOMER_CANCELLED", null));
        assertThat(voided.status()).isEqualTo(DisclosureStatus.VOID);
        assertThat(s.text("SELECT disclosure_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", s.w.tenant.value(), id.value()))
                .as("봉인 후 무효는 번호를 유지한다(재사용 없음)").isEqualTo(sealed.number().orElseThrow().value());
        s.artifactsOf(id).forEach(a -> assertThatThrownBy(() -> s.bucket.delete(a.storageKey())).isInstanceOf(ObjectLockedException.class));
        assertThat(s.sealReasoned().number().orElseThrow().sequence()).as("다음 봉인은 다음 번호").isEqualTo(2);
    }

    @Test
    void supersedeCreatesTheNextVersionPinnedByReResolutionOfTheOriginalConsultDate() {
        SealService.Outcome sealed = s.sealReasoned();
        DisclosureId original = sealed.id();
        SealScenarios.retroactiveTenantRule(s.w);                     // 원본 고정 뒤 상담일에 걸치는 사규가 생겼다
        assertThat(s.lifecycle.supersede(s.w.tenant, WorkflowSetup.AGENT, original, new LifecycleReason("CONTENT_ERROR", null)).rejection())
                .contains(LifecycleService.Rejection.ROLE_REQUIRED);

        LifecycleService.Outcome o = s.lifecycle.supersede(s.w.tenant, SealSetup.MANAGER, original, new LifecycleReason("CONTENT_ERROR", "보험료 예시 오기"));
        assertThat(o.status()).isEqualTo(DisclosureStatus.SUPERSEDED);
        DisclosureId next = o.newVersion().orElseThrow();
        String t = s.w.tenant.value();
        assertThat(s.text("SELECT status || '|' || superseded_by_id::text || '|' || supersede_reason_code || '|' || supersede_reason_text"
                + " FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", t, original.value()))
                .isEqualTo("SUPERSEDED|" + next.value() + "|CONTENT_ERROR|보험료 예시 오기");
        assertThat(s.audit().stream().filter(r -> r.entry().action() == AuditAction.DISCLOSURE_SUPERSEDE).toList().getLast().entry().detail()
                .toString()).as("사유 텍스트는 감사에 없다").doesNotContain("오기").contains("CONTENT_ERROR", "reasonTextLength");
        assertThat(s.text("SELECT status || '|' || version || '|' || supersedes_id::text || '|' || consult_date::text || '|' || rule_version_id"
                + " || '|' || coalesce(tenant_rule_version_id, '-') || '|' || coalesce(disclosure_no, '-') FROM disclosure"
                + " WHERE tenant_id = ? AND disclosure_id = ?", t, next.value()))
                .isEqualTo("DRAFT|2|" + original.value() + "|2026-09-23|DISC-2026-07|HOUSE-2026|-");
        assertThat(s.count("SELECT count(*) FROM disclosure_item WHERE tenant_id = ? AND disclosure_id = ?", t, next.value())).isEqualTo(3);
        assertThat(s.count("SELECT count(*) FROM disclosure_item WHERE tenant_id = ? AND disclosure_id = ? AND grade_status IS NOT NULL", t,
                next.value())).as("등급은 복제하지 않는다").isZero();
        assertThat(s.count("SELECT count(*) FROM recommendation WHERE tenant_id = ? AND disclosure_id = ?", t, next.value()))
                .as("추천사유는 복제하지 않는다(절대 규칙 7)").isZero();
        assertThat(s.actionsFor(original).getLast()).isEqualTo(AuditAction.DISCLOSURE_SUPERSEDE);
        assertThat(s.actionsFor(next)).containsExactly(AuditAction.DISCLOSURE_CREATE);
        s.artifactsOf(original).forEach(a -> assertThat(s.bucket.retention(a.storageKey())).as("원본 산출물 잠금 유지").isPresent());

        // 새 버전은 처음부터 다시: 비교 → 산출 → 사유 → 봉인(다음 번호)
        assertThat(s.w.service.compare(s.w.tenant, WorkflowSetup.AGENT, next).status()).isEqualTo(DisclosureStatus.COMPARED);
        s.w.service.requestGrades(s.w.tenant, WorkflowSetup.AGENT, next);
        s.w.service.setRecommendations(s.w.tenant, WorkflowSetup.AGENT, next, List.of(new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null),
                new AgentReason(3, List.of(ReasonCode.of("COVERAGE")), null)));
        SealService.Outcome resealed = s.seal.seal(s.w.tenant, WorkflowSetup.AGENT, next);
        assertThat(resealed.sealed()).as("%s", resealed.rejections()).isTrue();
        assertThat(resealed.number().orElseThrow().sequence()).isEqualTo(2);
    }

    @Test
    void reasonCodesComeFromThePinnedRuleAndAreWrittenOnce() {
        DisclosureId id = s.w.reasoned();
        String t = s.w.tenant.value();
        assertThat(s.lifecycle.voidDisclosure(s.w.tenant, WorkflowSetup.AGENT, id, new LifecycleReason("NOT_IN_RULE", null)).rejection())
                .contains(LifecycleService.Rejection.REASON_CODE_UNKNOWN);
        assertThat(s.lifecycle.voidDisclosure(s.w.tenant, WorkflowSetup.AGENT, id, new LifecycleReason("OTHER", null)).rejection())
                .as("OTHER는 requiresText").contains(LifecycleService.Rejection.REASON_TEXT_REQUIRED);
        assertThat(s.lifecycle.voidDisclosure(s.w.tenant, WorkflowSetup.AGENT, id, new LifecycleReason("OTHER", "가".repeat(501))).rejection())
                .as("lifecycleReasonTextMaxLength 500").contains(LifecycleService.Rejection.REASON_TEXT_TOO_LONG);
        assertThat(s.text("SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", t, id.value())).isEqualTo("REASONED");
        assertThat(s.lifecycle.voidDisclosure(s.w.tenant, WorkflowSetup.AGENT, id, new LifecycleReason("OTHER", "가".repeat(500))).applied())
                .isTrue();
        // 사유는 한 번만 쓴다(V8 GD100) — 소유 롤로 고쳐 써도 DB가 거부한다
        assertThat(TriggerAssertions.sqlStateOf(() -> s.w.db.seed(t, c -> com.ga.disclosure.infra.testing.SeedData.exec(c,
                "UPDATE disclosure SET void_reason_code = 'DUPLICATE' WHERE tenant_id = ? AND disclosure_id = ?", t, id.value()))))
                .isEqualTo("GD100");
    }

    @Test
    void supersedeIsOnlyForSealedDisclosures() {
        DisclosureId id = s.w.reasoned();
        assertThatThrownBy(() -> s.lifecycle.supersede(s.w.tenant, SealSetup.MANAGER, id, new LifecycleReason("CONTENT_ERROR", null))).isInstanceOf(IllegalTransition.class);
        assertThat(s.audit().getLast().entry().action()).isEqualTo(AuditAction.COMMAND_FAILED);
    }

    @Test
    void flagsOfRulesThatNoLongerFailAreResolvedAtSeal() {
        DisclosureId id = SealScenarios.reasonedWithTempProduct(s.w, "Q-2026-0301");
        assertThat(flagResolution(id, "VALIDATION_OVERRIDE")).isEqualTo("OPEN");
        // 임시등록 항목을 빼고 다시 진행 — R-TEMP-PRODUCT·R-GRADE-UNAVAILABLE은 봉인 시점에 더는 실패하지 않는다
        s.w.service.replaceItems(s.w.tenant, WorkflowSetup.AGENT, id, WorkflowSetup.threeItems());
        s.w.service.requestGrades(s.w.tenant, WorkflowSetup.AGENT, id);
        s.w.service.setRecommendations(s.w.tenant, WorkflowSetup.AGENT, id, List.of(new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null),
                new AgentReason(3, List.of(ReasonCode.of("COVERAGE")), null)));
        assertThat(s.seal.seal(s.w.tenant, WorkflowSetup.AGENT, id).sealed()).isTrue();
        assertThat(s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ? AND disclosure_id = ? AND type = 'VALIDATION_OVERRIDE'"
                + " AND resolution = 'RESOLVED_AT_SEAL' AND resolved_by = 'SYSTEM'", s.w.tenant.value(), id.value())).isEqualTo(2);
    }

    // ------------------------------------------------------------------ Phase 4 G10: 서명 진행 중 무효·정정

    private String sessionStatus(SignSetup x, String sessionId) {
        return x.s.text("SELECT status || coalesce('/' || revoke_reason, '') FROM sign_session WHERE tenant_id = ? AND session_id = ?::uuid",
                x.w.tenant.value(), sessionId);
    }

    @Test
    void voidingWhileSigningRevokesOpenSessionsAndKeepsSignatures() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId waiting = x.sealed();
            String token = x.issue(waiting, com.ga.disclosure.domain.enums.SignatureChannel.REMOTE_LINK);
            String sessionId = x.w.in(() -> x.sessions.openFor(waiting)).getFirst().sessionId().toString();
            x.clock.advance(java.time.Duration.ofMinutes(1));
            assertThat(x.lifecycle.voidDisclosure(x.w.tenant, SealSetup.MANAGER, waiting, new LifecycleReason("CUSTOMER_CANCELLED", null)).applied())
                    .isTrue();
            assertThat(sessionStatus(x, sessionId)).isEqualTo("REVOKED/DOCUMENT_VOIDED");
            assertThatThrownBy(() -> x.sessionService.open(token)).isExactlyInstanceOf(com.ga.disclosure.sign.token.SignTokenRejected.class);

            DisclosureId signed = x.sealedFor(x.newSigner("가상서명고객2"));
            x.customerSignsOnTouchPad(signed);
            x.lifecycle.voidDisclosure(x.w.tenant, SealSetup.MANAGER, signed, new LifecycleReason("CUSTOMER_CANCELLED", null));
            assertThat(x.status(signed)).isEqualTo("VOID");
            assertThat(x.signaturesOf(signed)).as("signatures stay on the voided original").hasSize(1);
        }
    }

    @Test
    void supersedingWhileSigningRevokesTheOldTokenAndCarriesNoSignatureOver() {
        try (SignSetup x = new SignSetup(new SealSetup(WorkflowSetup.withRule(body -> body.put("signOrder", "PARALLEL"))))) {
            DisclosureId original = x.sealed();
            assertThat(x.agentSigns(original).accepted()).isTrue();             // PARALLEL: 설계사가 먼저 서명 → PARTIALLY_SIGNED
            String token = x.issue(original, com.ga.disclosure.domain.enums.SignatureChannel.TOUCH_PAD);
            String sessionId = x.w.in(() -> x.sessions.openFor(original)).getFirst().sessionId().toString();
            x.clock.advance(java.time.Duration.ofMinutes(1));
            LifecycleService.Outcome o = x.lifecycle.supersede(x.w.tenant, SealSetup.MANAGER, original, new LifecycleReason("CONTENT_ERROR", null));
            DisclosureId next = o.newVersion().orElseThrow();
            assertThat(sessionStatus(x, sessionId)).isEqualTo("REVOKED/DOCUMENT_SUPERSEDED");
            assertThat(x.signaturesOf(original)).as("the agent signature stays on version 1").hasSize(1);
            assertThat(x.signaturesOf(next)).as("nothing carries over").isEmpty();
            assertThat(x.w.in(() -> x.sessions.openFor(next))).isEmpty();
            x.readyTouchPadQuietly(token);
            assertThatThrownBy(() -> x.signService.capture(token, SignSetup.capture("tablet-1", null)))
                    .as("G1: the old version's token never signs the correction").isExactlyInstanceOf(com.ga.disclosure.sign.token.SignTokenRejected.class);
            assertThat(x.signaturesOf(next)).isEmpty();
        }
    }
}
