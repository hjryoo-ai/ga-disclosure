package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.sign.token.SignTokenRejected;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.sign.IdentityInputs;
import com.ga.disclosure.workflow.sign.SignRejection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G3 고객 서명 세션(설계서 §6.5, 4 계획 §2): 토큰은 한 번 쓰이고(USED), TTL이 지나면·재발급되면·모르는 토큰이면 같은 예외 하나로 거부되며 세션 행은
 * 바뀌지 않는다(감사 {@code SIGN_SESSION_DENIED}에 사유 코드만). 본인확인 실패가 {@code maxFailures}에 닿으면 세션 REVOKED(IDENTITY_FAILED) +
 * 플래그. 원격 링크 토큰은 설계사에게 돌아가지 않고 고객 번호로만 나간다. 토큰 원문은 DB 어디에도 없다(센티널 토큰 스캔).
 */
class SignSessionIT {

    private final SignSetup x = new SignSetup();

    @AfterEach
    void close() {
        x.close();
    }

    private String sessionColumn(String column, String sessionId) {
        return x.s.text("SELECT " + column + " FROM sign_session WHERE tenant_id = ? AND session_id = ?::uuid", x.w.tenant.value(), sessionId);
    }

    private List<String> deniedReasons() {
        return x.s.audit().stream().filter(r -> r.entry().action() == AuditAction.SIGN_SESSION_DENIED)
                .map(r -> r.entry().detail().path("reason").asString()).toList();
    }

    private static void rejected(Executable call) {
        assertThatThrownBy(call::execute).isExactlyInstanceOf(SignTokenRejected.class).hasMessage("sign token rejected");
    }

    @Test
    void aTokenIsUsedOnce() {
        DisclosureId id = x.sealed();
        String token = x.issue(id, SignatureChannel.TOUCH_PAD);
        x.readyTouchPad(token);
        assertThat(x.signService.capture(token, SignSetup.capture("tablet-1", null)).accepted()).isTrue();
        rejected(() -> x.signService.capture(token, SignSetup.capture("tablet-1", null)));
        rejected(() -> x.sessionService.open(token));
        assertThat(x.signaturesOf(id)).hasSize(1);
        assertThat(deniedReasons()).containsExactly("SESSION_CLOSED", "SESSION_CLOSED");
    }

    @Test
    void anElapsedSessionIsRefusedWithoutChangingItAndReissueRecordsTheExpiry() {
        DisclosureId id = x.sealed();
        SignSessionService.IssueOutcome first = x.sessionService.issue(Callers.of(x.w.tenant, SignSetup.AGENT), id, SignatureChannel.TOUCH_PAD);
        String token = first.token().orElseThrow().reveal();
        assertThat(x.sessionService.open(token)).isNotEmpty();
        x.clock.advance(Duration.ofMinutes(31));                     // TOUCH_PAD TTL 30분(룰 sessionTtlMinutes)
        rejected(() -> x.sessionService.open(token));
        String sessionId = first.sessionId().orElseThrow().toString();
        assertThat(sessionColumn("status", sessionId)).as("access does not record the expiry").isEqualTo("OPEN");
        assertThat(deniedReasons()).containsExactly("SESSION_EXPIRED");

        String next = x.issue(id, SignatureChannel.TOUCH_PAD);
        assertThat(sessionColumn("status", sessionId)).as("reissue records it").isEqualTo("EXPIRED");
        x.readyTouchPad(next);
        assertThat(x.signService.capture(next, SignSetup.capture("tablet-1", null)).accepted()).isTrue();
    }

    @Test
    void reissuingRevokesTheOpenSession() {
        DisclosureId id = x.sealed();
        SignSessionService.IssueOutcome first = x.sessionService.issue(Callers.of(x.w.tenant, SignSetup.AGENT), id, SignatureChannel.TOUCH_PAD);
        String old = first.token().orElseThrow().reveal();
        x.issue(id, SignatureChannel.TOUCH_PAD);
        String sessionId = first.sessionId().orElseThrow().toString();
        assertThat(sessionColumn("status", sessionId)).isEqualTo("REVOKED");
        assertThat(sessionColumn("revoke_reason", sessionId)).isEqualTo("REISSUED");
        rejected(() -> x.sessionService.open(old));
    }

    @Test
    void unknownMalformedAndForeignTokensAreTheSameRejection() {
        DisclosureId id = x.sealed();
        String real = x.issue(id, SignatureChannel.TOUCH_PAD);
        String secret = real.substring(real.indexOf('~') + 1);
        String unknown = x.w.tenant.value() + "~" + new StringBuilder(secret).reverse();
        rejected(() -> x.sessionService.open(unknown));
        rejected(() -> x.sessionService.open("not-a-token"));
        rejected(() -> x.sessionService.open("NOSUCHTENANT~" + secret));
        rejected(() -> x.sessionService.open(null));
        assertThat(deniedReasons()).as("only the bound tenant's unknown token is recorded").containsExactly("UNKNOWN_TOKEN");
        assertThat(x.s.audit().stream().filter(r -> r.entry().action() == AuditAction.SIGN_SESSION_DENIED).findFirst().orElseThrow()
                .entry().targetId()).isNull();
        assertThat(x.sessionService.open(real)).as("the real session is untouched").isNotEmpty();
        long forged = x.w.db.asApp("NOSUCHTENANT", c -> com.ga.disclosure.infra.testing.SeedData.longValue(c,
                "SELECT count(*) FROM audit_log WHERE tenant_id = ?", "NOSUCHTENANT"));
        assertThat(forged).as("a forged prefix leaves no row").isZero();
    }

    @Test
    void identityFailuresRevokeTheSessionAtTheRuleLimit() {
        DisclosureId id = x.sealed();
        String token = x.issue(id, SignatureChannel.REMOTE_LINK);
        for (int i = 1; i <= 4; i++) {
            SignSessionService.IdentityOutcome o = x.sessionService.verify(token, IdentityInputs.birthDate("1999-01-0" + i));
            assertThat(o.failures()).isEqualTo(i);
            assertThat(o.revoked()).isFalse();
        }
        SignSessionService.IdentityOutcome fifth = x.sessionService.verify(token, IdentityInputs.birthDate("not a date"));
        assertThat(fifth.revoked()).as("maxFailures = 5").isTrue();
        assertThat(x.openFlags(id)).extracting(DisclosureFlagPort.OpenFlag::type).containsExactly(DisclosureFlagPort.Type.IDENTITY_FAILED);
        rejected(() -> x.sessionService.verify(token, IdentityInputs.birthDate(SignSetup.BIRTH)));
        assertThat(x.s.audit().stream().filter(r -> r.entry().action() == AuditAction.SIGN_IDENTITY_CHECK).count()).isEqualTo(5);
    }

    @Test
    void aRemoteLinkGoesOnlyToTheCustomerAndIdentityPassesWithTheBirthDate() {
        DisclosureId id = x.sealed();
        SignSessionService.IssueOutcome o = x.sessionService.issue(Callers.of(x.w.tenant, SignSetup.AGENT), id, SignatureChannel.REMOTE_LINK);
        assertThat(o.token()).as("the agent never holds a remote link token").isEmpty();
        assertThat(o.sent()).isTrue();
        assertThat(x.notify.sent).hasSize(1);
        assertThat(sessionColumn("sent_at", o.sessionId().orElseThrow().toString())).isNotNull();
        assertThat(x.s.audit()).anyMatch(r -> r.entry().action() == AuditAction.CUSTOMER_PHONE_READ
                && r.entry().detail().path("purpose").asString().equals("REMOTE_LINK"));
        String token = x.notify.last().reveal();

        SignService.Outcome early = x.signService.capture(token, SignSetup.capture("phone-1", "203.0.113.5"));
        assertThat(early.rejections()).containsExactly(SignRejection.IDENTITY_INCOMPLETE);
        SignSessionService.IdentityOutcome passed = x.sessionService.verify(token, IdentityInputs.birthDate(SignSetup.BIRTH_COMPACT));
        assertThat(passed.missing()).isEmpty();
        assertThat(passed.results()).extracting(r -> r.method()).containsExactly(IdentityMethod.LINK_POSSESSION, IdentityMethod.BIRTH_DATE);
        x.clock.advance(Duration.ofMinutes(3));
        assertThat(x.signService.capture(token, SignSetup.capture("phone-1", "203.0.113.5")).accepted()).isTrue();
        assertThat(x.s.audit()).anyMatch(r -> r.entry().action() == AuditAction.CUSTOMER_VIEW
                && r.entry().detail().path("reason").asString().equals("IDENTITY_CHECK"));
    }

    @Test
    void anotherAgentCannotIssueOrConfirmFaceToFace() {
        DisclosureId id = x.sealed();
        // 6A: 다른 설계사는 범위(OWN) 밖 — 인가 거부(404). 담당 검사(AGENT_NOT_ASSIGNED)는 같은 주체의 CLI 대리 실행에서 드러난다
        assertThatThrownBy(() -> x.sessionService.issue(Callers.of(x.w.tenant, SignSetup.STRANGER), id, SignatureChannel.TOUCH_PAD))
                .isInstanceOf(com.ga.disclosure.workflow.authz.AuthorizationDenied.class);
        assertThat(x.sessionService.issue(Callers.cli(x.w.tenant, SignSetup.STRANGER), id, SignatureChannel.TOUCH_PAD).rejections())
                .containsExactly(SignRejection.AGENT_NOT_ASSIGNED);
        String token = x.issue(id, SignatureChannel.TOUCH_PAD);
        assertThatThrownBy(() -> x.sessionService.confirmFaceToFace(Callers.of(x.w.tenant, SignSetup.STRANGER), token))
                .isInstanceOf(com.ga.disclosure.workflow.authz.AuthorizationDenied.class);
        assertThat(x.sessionService.confirmFaceToFace(Callers.cli(x.w.tenant, SignSetup.STRANGER), token).rejections()).containsExactly(SignRejection.AGENT_NOT_ASSIGNED);
        x.sessionService.recordView(token, true, 10);
        assertThat(x.signService.capture(token, SignSetup.capture("tablet-1", null)).rejections()).containsExactly(SignRejection.IDENTITY_INCOMPLETE);
    }

    @Test
    void theTokenPlaintextIsNowhereInTheDatabase() {
        DisclosureId id = x.sealed();
        String touch = x.issue(id, SignatureChannel.TOUCH_PAD);
        x.readyTouchPad(touch);
        x.signService.capture(touch, SignSetup.capture("tablet-1", null));
        for (String token : List.of(touch)) {
            String secret = token.substring(token.indexOf('~') + 1);
            assertThat(x.s.count("SELECT count(*) FROM sign_session WHERE tenant_id = ? AND (token_hash LIKE '%' || ? || '%')",
                    x.w.tenant.value(), secret)).isZero();
            assertThat(x.s.count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND (detail::text LIKE '%' || ? || '%' "
                    + "OR coalesce(target_id, '') LIKE '%' || ? || '%' OR actor_subject LIKE '%' || ? || '%')", x.w.tenant.value(), secret, secret,
                    secret)).isZero();
            assertThat(x.s.count("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND payload::text LIKE '%' || ? || '%'",
                    x.w.tenant.value(), secret)).isZero();
            assertThat(x.s.count("SELECT count(*) FROM signature WHERE tenant_id = ? AND (identity_check::text || coalesce(device::text, '')) "
                    + "LIKE '%' || ? || '%'", x.w.tenant.value(), secret)).isZero();
        }
        List<AuditRecord> audit = x.s.audit();
        assertThat(audit.toString()).doesNotContain(touch.substring(touch.indexOf('~') + 1));
    }
}
