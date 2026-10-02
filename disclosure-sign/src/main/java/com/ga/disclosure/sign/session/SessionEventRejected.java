package com.ga.disclosure.sign.session;

import java.util.Objects;

/** 상태표 밖의 세션 사건(닫힌 세션에 대한 열람·본인확인·서명 등). 세션은 바뀌지 않는다 — 업무 거부로 응답한다. */
public final class SessionEventRejected extends RuntimeException {

    private final SessionStatus from;
    private final SessionEvent event;

    public SessionEventRejected(SessionStatus from, SessionEvent event) {
        super(event + " is not accepted by a " + from + " session");
        this.from = Objects.requireNonNull(from, "from");
        this.event = Objects.requireNonNull(event, "event");
    }

    public SessionStatus from() {
        return from;
    }

    public SessionEvent event() {
        return event;
    }
}
