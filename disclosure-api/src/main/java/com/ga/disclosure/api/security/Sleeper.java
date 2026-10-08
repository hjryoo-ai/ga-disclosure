package com.ga.disclosure.api.security;

import java.time.Duration;

/**
 * 응답 패딩의 대기(6A 계획 §5.3). 운영은 {@link #threadSleeper()}, 시험은 기록형으로 바꿔 "모든 공개 응답이 한 번, 하한 − 경과만큼"을 결정론으로 단언한다.
 * {@code elapsed}는 관찰용이다(대기 계산은 {@link ResponsePadding}이 한다).
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration requested, Duration elapsed);

    static Sleeper threadSleeper() {
        return (requested, elapsed) -> {
            if (requested.isZero() || requested.isNegative()) {
                return;
            }
            try {
                Thread.sleep(requested);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
