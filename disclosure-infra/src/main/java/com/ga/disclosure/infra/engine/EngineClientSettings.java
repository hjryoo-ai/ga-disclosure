package com.ga.disclosure.infra.engine;

import java.time.Duration;
import java.util.Objects;

/**
 * 엔진 호출 파라미터. 재시도는 <b>연결 실패에만</b>(요청이 엔진에 닿지 않았음이 확실한 경우) — 산출 POST는 멱등이 아니므로 요청을 보낸 뒤의
 * 타임아웃이나 응답을 받은 뒤의 오류는 재시도하지 않는다(3A 지시문 §4).
 *
 * @param maxAttempts 연결 시도 최대 횟수(1 = 재시도 없음)
 */
public record EngineClientSettings(Duration connectTimeout, Duration requestTimeout, int maxAttempts) {

    public EngineClientSettings {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("maxAttempts must be 1..5");
        }
    }

    public static EngineClientSettings defaults() {
        return new EngineClientSettings(Duration.ofSeconds(2), Duration.ofSeconds(5), 3);
    }
}
