package com.ga.disclosure.audit.tsa;

import java.util.Objects;

/**
 * TSA 실패: 불가(전송)·거부(상태·nonce·imprint·서명·신뢰). 어느 쪽이든 영수증을 쓰지 않으며 봉인·서명·완료를 막지 않는다 — 앵커는 남고
 * 다음 실행이 둘째 배치로 잇는다(5 계획 §3·§8.1). 메시지는 사유 코드와 짧은 설명뿐이다.
 */
public final class TimestampFailure extends RuntimeException {

    public enum Kind { UNAVAILABLE, REJECTED }

    private final Kind kind;
    private final String reason;

    public TimestampFailure(Kind kind, String reason, Throwable cause) {
        super(kind + ": " + reason, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public TimestampFailure(Kind kind, String reason) {
        this(kind, reason, null);
    }

    public Kind kind() {
        return kind;
    }

    public String reason() {
        return reason;
    }
}
