package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.sign.IdentityInputs;
import com.ga.disclosure.workflow.sign.SignRejection;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2 서명 룰은 데이터다(설계서 §6.5, CLAUDE.md 절대 규칙 4): 같은 빌드에서 GLOBAL 룰 본문만 바꾼 테넌트가 다르게 동작한다 — 순서 PARALLEL, 관리자 확인
 * OFF(서명자 2인), PAPER_SCAN 채널 끄기, 본인확인 수단 교체, 서명 기한. 코드에는 역할·채널·수단의 닫힌 어휘만 있다(기준 동작은 CompletionIT·
 * SignSessionIT의 DISC-2026-07).
 */
class SignRulesAsDataIT {

    private static SignSetup tenant(Consumer<ObjectNode> edit) {
        return new SignSetup(new SealSetup(WorkflowSetup.withRule(edit)));
    }

    @Test
    void parallelOrderLetsTheAgentSignFirst() {
        try (SignSetup x = tenant(body -> body.put("signOrder", "PARALLEL"))) {
            DisclosureId id = x.sealed();
            SignService.Outcome agentFirst = x.agentSigns(id);
            assertThat(agentFirst.accepted()).as("%s", agentFirst.rejections()).isTrue();
            assertThat(x.customerSignsOnTouchPad(id).accepted()).isTrue();
            assertThat(x.managerConfirms(id).completed()).isTrue();
            assertThat(x.status(id)).isEqualTo("COMPLETED");
        }
    }

    @Test
    void managerConfirmOffCompletesWithTwoSigners() {
        try (SignSetup x = tenant(body -> {
            body.put("managerConfirmMode", "OFF");
            body.putArray("signerSet").add("CUSTOMER").add("AGENT");
        })) {
            DisclosureId id = x.sealed();
            assertThat(x.customerSignsOnTouchPad(id).completed()).isFalse();
            SignService.Outcome agent = x.agentSigns(id);
            assertThat(agent.completed()).isTrue();
            assertThat(x.status(id)).isEqualTo("COMPLETED");
        }
        try (SignSetup x = tenant(body -> {
            body.put("managerConfirmMode", "OFF");
            body.putArray("signerSet").add("CUSTOMER").add("AGENT");
        })) {
            DisclosureId id = x.sealed();
            x.customerSignsOnTouchPad(id);
            assertThat(x.managerConfirms(id).rejections()).contains(SignRejection.MANAGER_CONFIRM_DISABLED);
        }
    }

    @Test
    void aDisabledChannelIssuesNoSession() {
        try (SignSetup x = tenant(body -> ((ObjectNode) body.get("channels").get("PAPER_SCAN")).put("enabled", false))) {
            DisclosureId id = x.sealed();
            SignSessionService.IssueOutcome o = x.sessionService.issue(x.w.tenant, SignSetup.AGENT, id, SignatureChannel.PAPER_SCAN);
            assertThat(o.rejections()).containsExactly(SignRejection.CHANNEL_DISABLED);
            assertThat(x.sessionService.issue(x.w.tenant, SignSetup.AGENT, id, SignatureChannel.TOUCH_PAD).issued()).isTrue();
        }
    }

    @Test
    void swappingIdentityMethodsChangesWhatACaptureNeeds() {
        try (SignSetup x = tenant(body -> ((ObjectNode) body.get("identityCheck")).putArray("TOUCH_PAD").add("BIRTH_DATE"))) {
            DisclosureId id = x.sealed();
            String token = x.issue(id, SignatureChannel.TOUCH_PAD);
            x.readyTouchPad(token);                                   // 열람 + 대면 확인은 이 룰에서 요구 수단이 아니다
            SignService.Outcome before = x.signService.capture(token, SignSetup.capture("tablet-1", null));
            assertThat(before.rejections()).containsExactly(SignRejection.IDENTITY_INCOMPLETE);
            assertThat(x.sessionService.verify(token, IdentityInputs.birthDate(SignSetup.BIRTH)).missing()).isEmpty();
            assertThat(x.signService.capture(token, SignSetup.capture("tablet-1", null)).accepted()).isTrue();
        }
    }

    @Test
    void theSignDeadlineIsData() {
        try (SignSetup x = tenant(body -> body.put("signDeadlineDays", 0))) {
            DisclosureId id = x.sealed();                               // 봉인 2026-09-23 10:00 KST → 기한 끝 같은 날 23:59:59.999999 KST
            x.clock.set(java.time.Instant.parse("2026-09-23T14:59:59Z"));
            assertThat(x.sessionService.issue(x.w.tenant, SignSetup.AGENT, id, SignatureChannel.TOUCH_PAD).issued()).isTrue();
            x.clock.advance(Duration.ofSeconds(1));
            assertThat(x.sessionService.issue(x.w.tenant, SignSetup.AGENT, id, SignatureChannel.TOUCH_PAD).rejections())
                    .containsExactly(SignRejection.DEADLINE_PASSED);
        }
    }
}
