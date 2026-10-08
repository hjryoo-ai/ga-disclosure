package com.ga.disclosure.rules.resolve;

/**
 * 통지 재시도 백오프(룰 {@code notify.retry}, 6A 계획 §7 — 정수 초, 지터 없음):
 * <pre>delay(n) = min(maxDelaySeconds, initialDelaySeconds × multiplier^(n−1))     n = 실패 후 시도 수(1부터)</pre>
 * {@code maxAttempts}번째 실패에서 소진(DEAD)이다. 곱셈이 넘치면 상한이다.
 */
public record NotifyRetryRule(int maxAttempts, int initialDelaySeconds, int multiplier, int maxDelaySeconds) {

    public NotifyRetryRule {
        if (maxAttempts < 1 || initialDelaySeconds < 1 || multiplier < 1 || maxDelaySeconds < initialDelaySeconds) {
            throw new IllegalArgumentException("notify.retry needs maxAttempts, initialDelaySeconds, multiplier ≥ 1 and maxDelaySeconds ≥ initialDelaySeconds");
        }
    }

    /** {@code failedAttempts}번째 실패 뒤 다음 시도까지의 초. */
    public long delaySeconds(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts starts at 1");
        }
        long delay = initialDelaySeconds;
        for (int i = 1; i < failedAttempts; i++) {
            try {
                delay = Math.multiplyExact(delay, (long) multiplier);
            } catch (ArithmeticException overflow) {
                return maxDelaySeconds;
            }
            if (delay >= maxDelaySeconds) {
                return maxDelaySeconds;
            }
        }
        return Math.min(delay, maxDelaySeconds);
    }

    /** 이 실패로 소진되는가. */
    public boolean exhaustedAfter(int failedAttempts) {
        return failedAttempts >= maxAttempts;
    }
}
