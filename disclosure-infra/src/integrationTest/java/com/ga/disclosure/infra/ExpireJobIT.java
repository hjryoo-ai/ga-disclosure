package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.disclosure.IllegalTransition;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.sign.token.SignTokenRejected;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.ExpireService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G9 만료 배치(4 계획 §7.4): 서명 기한 끝(봉인일 2026-09-23 KST + 7일 = 2026-09-30 23:59:59.999999 KST)까지는 그대로, 1µs 뒤부터 EXPIRED — OPEN
 * 세션 전부 REVOKED(DOCUMENT_EXPIRED), 플래그 SIGN_EXPIRED, 받은 서명은 남고 만료 문서에는 더 서명할 수 없다. 같은 배치가 TTL이 지난 세션을 EXPIRED로
 * 기록한다. 두 번째 실행은 NOOP.
 */
class ExpireJobIT {

    private static final Actor OPERATOR = new Actor("operator@test", "OPERATOR");

    private final SignSetup x = new SignSetup();
    private final ExpireService expire = new ExpireService(x.w.deps(x.clock), x.sessions);

    @AfterEach
    void close() {
        x.close();
    }

    private String sessionStatus(String sessionId) {
        return x.s.text("SELECT status || coalesce('/' || revoke_reason, '') FROM sign_session WHERE tenant_id = ? AND session_id = ?::uuid",
                x.w.tenant.value(), sessionId);
    }

    @Test
    void theLastInstantOfTheDeadlineDayIsStillOpenAndTheNextOneExpires() {
        DisclosureId id = x.sealed();
        ExpireService.Report atDeadline = expire.run(Callers.of(x.w.tenant, OPERATOR), SignSetup.DEADLINE_END, 100);
        assertThat(atDeadline.expired()).isEmpty();
        assertThat(atDeadline.stillOpen()).isEqualTo(1);
        assertThat(x.status(id)).isEqualTo("SEALED");

        // 문서 만료가 닫는 세션은 TTL이 지났어도 REVOKED(DOCUMENT_EXPIRED)다 — 세션 만료는 서명 기한 끝을 넘지 않는다
        String token = x.issue(id, SignatureChannel.REMOTE_LINK);
        String sessionId = x.w.in(() -> x.sessions.openFor(id)).getFirst().sessionId().toString();

        ExpireService.Report after = expire.run(Callers.of(x.w.tenant, OPERATOR), SignSetup.DEADLINE_END.plusNanos(1000), 100);
        assertThat(after.expired()).containsExactly(id);
        assertThat(x.status(id)).isEqualTo("EXPIRED");
        assertThat(sessionStatus(sessionId)).isEqualTo("REVOKED/DOCUMENT_EXPIRED");
        assertThat(x.openFlags(id)).extracting(DisclosureFlagPort.OpenFlag::type).contains(DisclosureFlagPort.Type.SIGN_EXPIRED);
        assertThat(x.s.actionsFor(id)).contains(AuditAction.SIGN_SESSION_REVOKE, AuditAction.DISCLOSURE_EXPIRE);
        assertThatThrownBy(() -> x.sessionService.open(token)).isExactlyInstanceOf(SignTokenRejected.class);

        ExpireService.Report again = expire.run(Callers.of(x.w.tenant, OPERATOR), SignSetup.DEADLINE_END.plusSeconds(60), 100);
        assertThat(again.expired()).isEmpty();
        assertThat(again.stillOpen()).isZero();
    }

    @Test
    void signaturesSurviveExpiryAndAnExpiredDisclosureTakesNoMore() {
        DisclosureId id = x.sealed();
        assertThat(x.customerSignsOnTouchPad(id).accepted()).isTrue();
        expire.run(Callers.of(x.w.tenant, OPERATOR), Instant.parse("2026-10-01T00:00:00Z"), 100);
        assertThat(x.status(id)).isEqualTo("EXPIRED");
        assertThat(x.signaturesOf(id)).hasSize(1);
        assertThatThrownBy(() -> x.agentSigns(id)).isInstanceOf(IllegalTransition.class);
        assertThat(x.s.audit()).anyMatch(r -> r.entry().action() == AuditAction.COMMAND_FAILED
                && r.entry().detail().path("code").asString().equals("ILLEGAL_TRANSITION"));
        assertThatThrownBy(() -> x.sessionService.issue(Callers.of(x.w.tenant, SignSetup.AGENT), id, SignatureChannel.TOUCH_PAD))
                .isInstanceOf(IllegalTransition.class);
    }

    @Test
    void elapsedSessionsAreRecordedAsExpiredWithoutTouchingTheDisclosure() {
        DisclosureId id = x.sealed();
        x.issue(id, SignatureChannel.TOUCH_PAD);                               // TTL 30분
        String sessionId = x.w.in(() -> x.sessions.openFor(id)).getFirst().sessionId().toString();
        x.clock.advance(Duration.ofMinutes(31));
        ExpireService.Report r = expire.run(Callers.of(x.w.tenant, OPERATOR), x.clock.instant(), 100);
        assertThat(r.sessionsExpired()).isEqualTo(1);
        assertThat(r.expired()).isEmpty();
        assertThat(sessionStatus(sessionId)).isEqualTo("EXPIRED");
        assertThat(x.status(id)).isEqualTo("SEALED");
        assertThat(x.s.actionsFor(id)).contains(AuditAction.SIGN_SESSION_EXPIRE);
        assertThat(expire.run(Callers.of(x.w.tenant, OPERATOR), x.clock.instant(), 100).sessionsExpired()).isZero();
    }
}
