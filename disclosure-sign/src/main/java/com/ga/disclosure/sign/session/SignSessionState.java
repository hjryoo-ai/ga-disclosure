package com.ga.disclosure.sign.session;

import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.sign.identity.IdentityIncomplete;
import com.ga.disclosure.sign.identity.IdentityPolicy;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 세션의 가변 부분(V8 {@code sign_session}의 상태·실패 횟수·통과 수단·열람 여부·취소 사유). 모든 변경은 {@link SessionStateTable}을 거치고
 * 새 값을 돌려준다(불변). DB 트리거 GD101과 같은 규칙: 실패는 +1, 통과 수단은 추가만, 열람은 1회 기록, OPEN을 떠나면 끝.
 *
 * @param revokeReason REVOKED일 때만 있다
 */
public record SignSessionState(SessionStatus status, int identityFailures, Set<IdentityMethod> identityPassed, boolean viewed,
                               SessionRevokeReason revokeReason) {

    public SignSessionState {
        Objects.requireNonNull(status, "status");
        if (identityFailures < 0) {
            throw new IllegalArgumentException("identityFailures < 0");
        }
        identityPassed = identityPassed.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(identityPassed));
        if ((status == SessionStatus.REVOKED) != (revokeReason != null)) {
            throw new IllegalArgumentException("revokeReason is present exactly when REVOKED");
        }
    }

    /** 발급 직후(V8 GD101: OPEN, 실패 0, 통과 없음, 열람 없음). */
    public static SignSessionState issued() {
        return new SignSessionState(SessionStatus.OPEN, 0, Set.of(), false, null);
    }

    /** 열람 완료 기록. 이미 열람했으면 그대로(열람 증거는 1회만 쓴다). */
    public SignSessionState view() {
        SessionStateTable.target(status, SessionEvent.OPEN_VIEW, SessionStatus.OPEN);
        return new SignSessionState(status, identityFailures, identityPassed, true, null);
    }

    public SignSessionState passIdentity(IdentityMethod method) {
        SessionStateTable.target(status, SessionEvent.IDENTITY_PASS, SessionStatus.OPEN);
        Set<IdentityMethod> passed = EnumSet.noneOf(IdentityMethod.class);
        passed.addAll(identityPassed);
        passed.add(Objects.requireNonNull(method, "method"));
        return new SignSessionState(status, identityFailures, passed, viewed, null);
    }

    /** 실패 1회. 상한({@code identityCheck.maxFailures})에 닿으면 REVOKED(IDENTITY_FAILED). */
    public SignSessionState failIdentity(int maxFailures) {
        int after = identityFailures + 1;
        if (IdentityPolicy.exhausted(after, maxFailures)) {
            SessionStateTable.target(status, SessionEvent.IDENTITY_FAIL, SessionStatus.REVOKED);
            return new SignSessionState(SessionStatus.REVOKED, after, identityPassed, viewed, SessionRevokeReason.IDENTITY_FAILED);
        }
        SessionStateTable.target(status, SessionEvent.IDENTITY_FAIL, SessionStatus.OPEN);
        return new SignSessionState(status, after, identityPassed, viewed, null);
    }

    /** 서명 수집 → USED. 룰이 요구하는 수단을 다 통과하지 않았으면 {@link IdentityIncomplete}(세션 불변). */
    public SignSessionState capture(List<IdentityMethod> required) {
        SessionStateTable.target(status, SessionEvent.CAPTURE, SessionStatus.USED);
        List<IdentityMethod> missing = IdentityPolicy.missing(required, identityPassed);
        if (!missing.isEmpty()) {
            throw new IdentityIncomplete(missing);
        }
        return new SignSessionState(SessionStatus.USED, identityFailures, identityPassed, viewed, null);
    }

    /** TTL 경과 기록(만료 배치·재발급 직전만 — 접근 시에는 상태를 바꾸지 않고 업무 거부한다, 4 계획 §2.3). */
    public SignSessionState elapse() {
        SessionStateTable.target(status, SessionEvent.TTL_ELAPSED, SessionStatus.EXPIRED);
        return new SignSessionState(SessionStatus.EXPIRED, identityFailures, identityPassed, viewed, null);
    }

    /** 재발급·문서 무효·정정·만료로 취소. */
    public SignSessionState revoke(SessionEvent cause) {
        SessionStateTable.target(status, cause, SessionStatus.REVOKED);
        return new SignSessionState(SessionStatus.REVOKED, identityFailures, identityPassed, viewed, SessionRevokeReason.of(cause));
    }
}
