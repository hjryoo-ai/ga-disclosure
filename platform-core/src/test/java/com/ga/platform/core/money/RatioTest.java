package com.ga.platform.core.money;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RatioTest {

    @ParameterizedTest
    @CsvSource({
            "84, 100, 84.0%",
            "137, 100, 137.0%",
            "1, 3, 33.3%",
            "2, 3, 66.7%",
            "1, 8, 12.5%",
            "1, 2000, 0.1%",
            "1, 2001, 0.0%",
            "0, 7, 0.0%",
            "-1, 3, -33.3%",
            "-1, 2001, 0.0%",
    })
    void percentWithOneDecimalHalfUp(long numerator, long denominator, String expected) {
        assertThat(Ratio.of(numerator, denominator).toPercentString()).isEqualTo(expected);
    }

    @Test
    void largeDenominatorDoesNotOverflowRounding() {
        assertThat(Ratio.of(1, Long.MAX_VALUE).toPercentString()).isEqualTo("0.0%");
    }

    @Test
    void rejectsNonPositiveDenominator() {
        assertThatThrownBy(() -> Ratio.of(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ratio.of(1, -3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotProduceWonNorBeOrdered() {
        assertThat(Arrays.stream(Ratio.class.getDeclaredMethods()).map(Method::getReturnType)).doesNotContain(Won.class);
        assertThat(Comparable.class.isAssignableFrom(Ratio.class)).isFalse();
    }
}
