package com.ga.disclosure.sign.session;

/** 고객 서명 세션 상태(V8 {@code sign_session.status}). OPEN만 사건을 받고 나머지는 끝 상태다. */
public enum SessionStatus {
    OPEN,
    USED,
    EXPIRED,
    REVOKED;

    public boolean isClosed() {
        return this != OPEN;
    }
}
