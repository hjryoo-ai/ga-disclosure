package com.ga.platform.core.time;

import com.ga.platform.core.testing.SeededCases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YmTest {

    private static final long SEED = 0x5EED_0000_0002L;

    static Stream<Arguments> months() {
        return SeededCases.of(SEED, r -> new Object[] {r.nextInt(1001, 9999), r.nextInt(1, 13)});
    }

    @ParameterizedTest
    @MethodSource("months")
    void nextPrevRoundTrip(int year, int month) {
        Ym ym = Ym.of(year, month);
        assertThat(ym.next().prev()).isEqualTo(ym);
        assertThat(ym.prev().next()).isEqualTo(ym);
        assertThat(ym.next()).isGreaterThan(ym);
        assertThat(ym.prev()).isLessThan(ym);
    }

    @ParameterizedTest
    @MethodSource("months")
    void parseFormatRoundTrip(int year, int month) {
        Ym ym = Ym.of(year, month);
        assertThat(Ym.parse(ym.value())).isEqualTo(ym);
        assertThat(ym.value()).hasSize(6);
    }

    @Test
    void yearBoundaries() {
        assertThat(Ym.of(2026, 12).next()).isEqualTo(Ym.of(2027, 1));
        assertThat(Ym.of(2027, 1).prev()).isEqualTo(Ym.of(2026, 12));
        assertThatThrownBy(() -> Ym.of(9999, 12).next()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rangeIsInclusiveAndOrdered() {
        assertThat(Ym.range(Ym.parse("202611"), Ym.parse("202702")))
                .extracting(Ym::value)
                .containsExactly("202611", "202612", "202701", "202702");
        assertThat(Ym.range(Ym.parse("202609"), Ym.parse("202609"))).containsExactly(Ym.parse("202609"));
        assertThatThrownBy(() -> Ym.range(Ym.parse("202610"), Ym.parse("202609"))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2026-09", "20269", "2026091", "202600", "202613", "099912", "abcdef"})
    void rejectsMalformed(String raw) {
        assertThatThrownBy(() -> Ym.parse(raw)).isInstanceOf(IllegalArgumentException.class);
    }
}
