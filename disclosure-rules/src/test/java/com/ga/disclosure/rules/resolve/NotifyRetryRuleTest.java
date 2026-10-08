package com.ga.disclosure.rules.resolve;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 6A 계획 §7 백오프 산식: delay(n) = min(max, initial × multiplier^(n−1)), 정수 초, 넘치면 상한. */
class NotifyRetryRuleTest {

    @Test
    void theBundleExampleWaitsSixtyThenDoublesAndIsExhaustedAtTheFifthFailure() {
        NotifyRetryRule r = new NotifyRetryRule(5, 60, 2, 3600);
        assertThat(r.delaySeconds(1)).isEqualTo(60);
        assertThat(r.delaySeconds(2)).isEqualTo(120);
        assertThat(r.delaySeconds(3)).isEqualTo(240);
        assertThat(r.delaySeconds(4)).isEqualTo(480);
        assertThat(r.exhaustedAfter(4)).isFalse();
        assertThat(r.exhaustedAfter(5)).isTrue();
    }

    @Test
    void theDelayIsCappedAndAnOverflowIsTheCap() {
        NotifyRetryRule r = new NotifyRetryRule(100, 60, 2, 3600);
        assertThat(r.delaySeconds(7)).isEqualTo(3600);          // 60·64 = 3840 > 3600
        assertThat(r.delaySeconds(99)).isEqualTo(3600);
        NotifyRetryRule huge = new NotifyRetryRule(100, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertThat(huge.delaySeconds(50)).isEqualTo(Integer.MAX_VALUE);
        assertThat(new NotifyRetryRule(3, 10, 1, 10).delaySeconds(3)).as("배수 1은 고정 간격").isEqualTo(10);
    }

    @Test
    void malformedRulesAndAttemptsAreRejected() {
        assertThatThrownBy(() -> new NotifyRetryRule(5, 60, 2, 59)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotifyRetryRule(0, 60, 2, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotifyRetryRule(5, 60, 0, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotifyRetryRule(5, 60, 2, 3600).delaySeconds(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
