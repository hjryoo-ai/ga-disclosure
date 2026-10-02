package com.ga.disclosure.infra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** 테스트 시계: 정한 시각에 머물고 테스트가 앞으로만 옮긴다(서명 기한·세션 TTL·대리 서명 시간 지표 경계). */
final class MovableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    MovableClock(Instant start, ZoneId zone) {
        this.now = Objects.requireNonNull(start, "start");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    void advance(Duration d) {
        if (d.isNegative()) {
            throw new IllegalArgumentException("the test clock only moves forward");
        }
        now = now.plus(d);
    }

    void set(Instant at) {
        if (at.isBefore(now)) {
            throw new IllegalArgumentException("the test clock only moves forward");
        }
        now = at;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
        return new MovableClock(now, z);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
