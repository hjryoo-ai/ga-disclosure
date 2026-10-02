package com.ga.disclosure.sign.session;

import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.sign.identity.IdentityIncomplete;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import static com.ga.disclosure.domain.enums.IdentityMethod.BIRTH_DATE;
import static com.ga.disclosure.domain.enums.IdentityMethod.LINK_POSSESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignSessionStateTest {

    private static final List<IdentityMethod> REMOTE = List.of(LINK_POSSESSION, BIRTH_DATE);

    @Test
    void customerPathViewPassBothThenCapture() {
        SignSessionState s = SignSessionState.issued().view().passIdentity(LINK_POSSESSION).passIdentity(BIRTH_DATE);
        assertThat(s.viewed()).isTrue();
        assertThat(s.identityPassed()).containsExactlyInAnyOrder(LINK_POSSESSION, BIRTH_DATE);
        SignSessionState used = s.capture(REMOTE);
        assertThat(used.status()).isEqualTo(SessionStatus.USED);
        assertThat(used.revokeReason()).isNull();
    }

    @Test
    void captureWithoutEveryRequiredMethodIsRejectedAndTheSessionStaysOpen() {
        SignSessionState s = SignSessionState.issued().passIdentity(LINK_POSSESSION);
        IdentityIncomplete e = org.assertj.core.api.Assertions.catchThrowableOfType(IdentityIncomplete.class, () -> s.capture(REMOTE));
        assertThat(e.missing()).containsExactly(BIRTH_DATE);
        assertThat(s.status()).isEqualTo(SessionStatus.OPEN);
    }

    @Test
    void failuresCountUpAndRevokeAtTheRuleMaximum() {
        SignSessionState s = SignSessionState.issued();
        for (int i = 1; i < 5; i++) {
            s = s.failIdentity(5);
            assertThat(s.status()).isEqualTo(SessionStatus.OPEN);
            assertThat(s.identityFailures()).isEqualTo(i);
        }
        SignSessionState revoked = s.failIdentity(5);
        assertThat(revoked.status()).isEqualTo(SessionStatus.REVOKED);
        assertThat(revoked.revokeReason()).isEqualTo(SessionRevokeReason.IDENTITY_FAILED);
        assertThat(revoked.identityFailures()).isEqualTo(5);
        // 상한은 데이터 — 1이면 첫 실패에 취소
        assertThat(SignSessionState.issued().failIdentity(1).status()).isEqualTo(SessionStatus.REVOKED);
        assertThatThrownBy(() -> SignSessionState.issued().failIdentity(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = SessionEvent.class, names = {"REISSUE", "DOCUMENT_VOID", "DOCUMENT_SUPERSEDE", "DOCUMENT_EXPIRE"})
    void documentAndReissueEventsRevokeWithTheirReason(SessionEvent event) {
        SignSessionState s = SignSessionState.issued().revoke(event);
        assertThat(s.status()).isEqualTo(SessionStatus.REVOKED);
        assertThat(s.revokeReason()).isEqualTo(SessionRevokeReason.of(event));
    }

    @Test
    void nonRevokingEventsCannotBeUsedAsRevokeCauses() {
        for (SessionEvent e : List.of(SessionEvent.OPEN_VIEW, SessionEvent.IDENTITY_PASS, SessionEvent.CAPTURE, SessionEvent.TTL_ELAPSED)) {
            assertThatThrownBy(() -> SignSessionState.issued().revoke(e)).as("%s", e).isInstanceOf(RuntimeException.class);
        }
    }

    static final List<UnaryOperator<SignSessionState>> EVERY_EVENT = List.of(
            SignSessionState::view,
            s -> s.passIdentity(LINK_POSSESSION),
            s -> s.failIdentity(5),
            s -> s.capture(List.of()),
            SignSessionState::elapse,
            s -> s.revoke(SessionEvent.REISSUE),
            s -> s.revoke(SessionEvent.DOCUMENT_VOID),
            s -> s.revoke(SessionEvent.DOCUMENT_SUPERSEDE),
            s -> s.revoke(SessionEvent.DOCUMENT_EXPIRE));

    @Test
    void closedSessionsRejectEveryEvent() {
        assertThat(EVERY_EVENT).hasSize(SessionEvent.values().length);
        for (SignSessionState closed : List.of(SignSessionState.issued().capture(List.of()), SignSessionState.issued().elapse(),
                SignSessionState.issued().revoke(SessionEvent.REISSUE))) {
            for (UnaryOperator<SignSessionState> event : EVERY_EVENT) {
                assertThatThrownBy(() -> event.apply(closed)).as("%s", closed.status()).isInstanceOf(SessionEventRejected.class);
            }
        }
    }

    @Test
    void invariants() {
        assertThatThrownBy(() -> new SignSessionState(SessionStatus.REVOKED, 0, Set.of(), false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignSessionState(SessionStatus.OPEN, 0, Set.of(), false, SessionRevokeReason.REISSUED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignSessionState(SessionStatus.OPEN, -1, Set.of(), false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SignSessionState.issued().view().view().viewed()).isTrue();
    }
}
