package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.sign.PaperScan;
import com.ga.disclosure.workflow.sign.SignRejection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 종이 스캔(설계서 §6.5 PAPER_SCAN, 4 계획 §7.2, 승인 Q10): 설계사가 스캔본 각주의 번호·해시 접두를 입력해 원본과 대조하고(OCR 없음, 불일치는 업무 거부 —
 * 감사에는 일치 여부만), 룰이 관리자 검토를 요구하면 {@code PAPER_SCAN_REVIEW}가 열려 해소 전에는 완료되지 않는다. 관리자가 서명자 집합에 있으면 관리자
 * 확인이 해소하고(같은 트랜잭션에서 완료), 없으면(OFF) 예외 승인 역할의 검토 뒤 COMPLETE 명령이 완료한다.
 */
class PaperScanIT {

    private static String[] numberAndPrefix(SignSetup x, DisclosureId id) {
        String no = x.s.text("SELECT disclosure_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", x.w.tenant.value(), id.value());
        String hash = x.s.text("SELECT canonical_hash FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", x.w.tenant.value(), id.value());
        return new String[]{no, hash.substring(0, 12)};
    }

    private static SignService.Outcome scan(SignSetup x, DisclosureId id, Actor agent, String prefixOverrideOrNull) {
        return scan(x, id, Callers.of(x.w.tenant, agent), prefixOverrideOrNull);
    }

    private static SignService.Outcome scan(SignSetup x, DisclosureId id, com.ga.disclosure.workflow.authz.Caller agent, String prefixOverrideOrNull) {
        String token = x.issue(id, SignatureChannel.PAPER_SCAN);
        x.sessionService.confirmFaceToFace(Callers.of(x.w.tenant, SignSetup.AGENT), token);         // 룰 identityCheck.PAPER_SCAN = [AGENT_FACE_TO_FACE]
        String[] footnote = numberAndPrefix(x, id);
        return x.signService.uploadPaperScan(agent, token, new PaperScan(SignSetup.png(), footnote[0],
                prefixOverrideOrNull == null ? footnote[1] : prefixOverrideOrNull));
    }

    private static boolean reviewOpen(SignSetup x, DisclosureId id) {
        return x.openFlags(id).stream().anyMatch(f -> f.type() == DisclosureFlagPort.Type.PAPER_SCAN_REVIEW);
    }

    @Test
    void theManagerConfirmationResolvesTheReviewAndCompletes() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId id = x.sealed();
            assertThat(scan(x, id, SignSetup.AGENT, null).accepted()).isTrue();
            assertThat(reviewOpen(x, id)).isTrue();
            x.agentSigns(id);
            assertThat(x.signService.reviewPaperScan(Callers.of(x.w.tenant, SignSetup.MANAGER), id).rejections())
                    .containsExactly(SignRejection.REVIEW_VIA_MANAGER_CONFIRM);
            assertThat(x.signService.managerConfirm(Callers.of(x.w.tenant, SignSetup.MANAGER), id, java.util.Set.of()).rejections())
                    .as("every flag of the disclosure must be acknowledged (approval Q9)").containsExactly(SignRejection.ACKNOWLEDGEMENT_MISSING);
            java.util.Set<java.util.UUID> all = x.allFlags(id);
            assertThat(all).isNotEmpty();
            SignService.Outcome manager = x.managerConfirms(id);
            assertThat(manager.completed()).isTrue();
            assertThat(x.signaturesOf(id).getLast().acknowledgedFlags()).containsExactlyInAnyOrderElementsOf(all);
            assertThat(reviewOpen(x, id)).isFalse();
            assertThat(x.signaturesOf(id).getFirst().scanMatchOrNull().matched()).isTrue();
            assertThat(x.s.audit()).anyMatch(r -> r.entry().action() == AuditAction.FLAG_RESOLVE
                    && r.entry().detail().path("resolution").asString().equals("PAPER_SCAN_REVIEWED"));
        }
    }

    @Test
    void aMismatchedFootnoteIsRejectedAndOnlyTheMatchIsAudited() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId id = x.sealed();
            SignService.Outcome o = scan(x, id, SignSetup.AGENT, "ffffffffffff");
            assertThat(o.rejections()).containsExactly(SignRejection.SCAN_MISMATCH);
            assertThat(x.signaturesOf(id)).isEmpty();
            String detail = x.s.audit().stream().filter(r -> r.entry().action() == AuditAction.DISCLOSURE_REJECT).findFirst().orElseThrow()
                    .entry().detail().toString();
            assertThat(detail).contains("\"hashPrefixMatched\":false").doesNotContain("ffffffffffff");
            // 6A: 다른 설계사는 범위 밖(인가 거부), 담당 검사는 같은 주체의 CLI 대리 실행에서
            assertThatThrownBy(() -> scan(x, id, SignSetup.STRANGER, null)).isInstanceOf(com.ga.disclosure.workflow.authz.AuthorizationDenied.class);
            assertThat(scan(x, id, Callers.cli(x.w.tenant, SignSetup.STRANGER), null).rejections()).contains(SignRejection.AGENT_NOT_ASSIGNED);
        }
    }

    @Test
    void withoutAManagerTheExceptionRoleReviewsAndTheCompleteCommandFinishes() {
        try (SignSetup x = new SignSetup(new SealSetup(WorkflowSetup.withRule(body -> {
            body.put("managerConfirmMode", "OFF");
            body.putArray("signerSet").add("CUSTOMER").add("AGENT");
        })))) {
            DisclosureId id = x.sealed();
            scan(x, id, SignSetup.AGENT, null);
            SignService.Outcome agent = x.agentSigns(id);
            assertThat(agent.completed()).as("the open review holds completion").isFalse();
            assertThat(x.status(id)).isEqualTo("PARTIALLY_SIGNED");
            SignService.Outcome held = x.signService.complete(Callers.of(x.w.tenant, SignSetup.AGENT), id);
            assertThat(held.rejections()).as("%s", held.completionResults()).containsExactly(SignRejection.PAPER_SCAN_REVIEW_OPEN);
            // 6A: 설계사에게 검토 칸이 없다(인가 거부) — 업무 규칙(exceptionApproval.role)은 CLI 대리 실행에서
            assertThatThrownBy(() -> x.signService.reviewPaperScan(Callers.of(x.w.tenant, SignSetup.AGENT), id))
                    .isInstanceOf(com.ga.disclosure.workflow.authz.AuthorizationDenied.class);
            assertThat(x.signService.reviewPaperScan(Callers.cli(x.w.tenant, SignSetup.AGENT), id).rejections())
                    .containsExactly(SignRejection.REVIEW_ROLE_REQUIRED);
            assertThat(x.signService.reviewPaperScan(Callers.of(x.w.tenant, SignSetup.MANAGER), id).accepted()).isTrue();
            assertThat(x.signService.reviewPaperScan(Callers.of(x.w.tenant, SignSetup.MANAGER), id).rejections()).containsExactly(SignRejection.NO_REVIEW_PENDING);
            SignService.Outcome done = x.signService.complete(Callers.of(x.w.tenant, SignSetup.AGENT), id);
            assertThat(done.completed()).isTrue();
            assertThat(x.status(id)).isEqualTo("COMPLETED");
        }
    }
}
