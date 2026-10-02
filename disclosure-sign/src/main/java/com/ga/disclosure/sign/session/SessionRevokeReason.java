package com.ga.disclosure.sign.session;

/** 세션 취소 사유(V8 {@code sign_session.revoke_reason} 닫힌 목록). 사건 → 사유 대응은 {@link #of(SessionEvent)}. */
public enum SessionRevokeReason {
    IDENTITY_FAILED,
    DOCUMENT_VOIDED,
    DOCUMENT_SUPERSEDED,
    DOCUMENT_EXPIRED,
    REISSUED;

    /** 세션을 취소하는 사건의 사유. 취소하지 않는 사건이면 예외. */
    public static SessionRevokeReason of(SessionEvent event) {
        return switch (event) {
            case IDENTITY_FAIL -> IDENTITY_FAILED;
            case DOCUMENT_VOID -> DOCUMENT_VOIDED;
            case DOCUMENT_SUPERSEDE -> DOCUMENT_SUPERSEDED;
            case DOCUMENT_EXPIRE -> DOCUMENT_EXPIRED;
            case REISSUE -> REISSUED;
            case OPEN_VIEW, IDENTITY_PASS, CAPTURE, TTL_ELAPSED -> throw new IllegalArgumentException(event + " does not revoke a session");
        };
    }
}
