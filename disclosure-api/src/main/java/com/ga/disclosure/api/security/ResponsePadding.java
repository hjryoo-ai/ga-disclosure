package com.ga.disclosure.api.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 공개 서명 응답의 시간 하한(6A 계획 §5.3, 승인 Q6): 성공·업무 거부·토큰 거부·없는 테넌트·한도 초과·예외 모두 같은 지점에서 {@code max(0, 하한 − 경과)}만큼
 * 기다린다 — 응답 시간으로 거부 사유(특히 테넌트 존재)를 가를 수 없게. 하한은 배포 설정 {@code ga.public-sign.min-response-millis}(기본값 없음, 1 이상).
 */
public final class ResponsePadding {

    private final Duration floor;
    private final Clock clock;
    private final Sleeper sleeper;

    public ResponsePadding(Duration floor, Clock clock, Sleeper sleeper) {
        this.floor = Objects.requireNonNull(floor, "floor");
        if (floor.toMillis() < 1) {
            throw new IllegalStateException("ga.public-sign.min-response-millis must be at least 1");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    public Duration floor() {
        return floor;
    }

    /** 요청 시작부터 지금까지를 하한까지 채운다. */
    public void pad(Instant start) {
        Duration elapsed = Duration.between(start, clock.instant());
        Duration requested = floor.minus(elapsed);
        sleeper.sleep(requested.isNegative() ? Duration.ZERO : requested, elapsed);
    }
}
