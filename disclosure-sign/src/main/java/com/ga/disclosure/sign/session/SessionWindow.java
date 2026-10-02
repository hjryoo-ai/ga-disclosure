package com.ga.disclosure.sign.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 세션 유효 구간(4 계획 §2.2): 만료 = min(발급 + TTL, 서명 기한 끝). TTL은 채널별 룰 값(REMOTE_LINK = {@code remoteLinkTtlHours},
 * TOUCH_PAD·PAPER_SCAN = {@code sessionTtlMinutes})이고 호출자가 룰에서 읽어 넘긴다. 만료 시각 이후의 접근은 업무 거부다.
 */
public final class SessionWindow {

    private SessionWindow() {
    }

    public static Instant expiresAt(Instant issuedAt, Duration ttl, Instant signDeadlineLastInstant) {
        Objects.requireNonNull(issuedAt, "issuedAt");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("session TTL must be positive: " + ttl);
        }
        Instant byTtl = issuedAt.plus(ttl);
        Instant end = byTtl.isBefore(signDeadlineLastInstant) ? byTtl : signDeadlineLastInstant;
        if (!end.isAfter(issuedAt)) {
            throw new IllegalArgumentException("session would expire before it is issued (sign deadline passed)");
        }
        return end;
    }

    /** {@code now}가 만료 시각을 지났는가(만료 시각까지는 유효하다). */
    public static boolean elapsed(Instant now, Instant expiresAt) {
        return now.isAfter(expiresAt);
    }
}
