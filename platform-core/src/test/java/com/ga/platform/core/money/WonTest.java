package com.ga.platform.core.money;

import com.ga.platform.core.testing.SeededCases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WonTest {

    private static final long SEED = 0x5EED_0000_0001L;
    private static final long BOUND = Long.MAX_VALUE / 4;

    static Stream<Arguments> triples() {
        return SeededCases.of(SEED, r -> new Object[] {r.nextLong(-BOUND, BOUND), r.nextLong(-BOUND, BOUND), r.nextLong(-BOUND, BOUND)});
    }

    static Stream<Arguments> overflowingPairs() {
        // a는 상한 근처, b는 a + b 가 Long.MAX_VALUE 를 넘도록 고른다.
        return SeededCases.of(SEED + 1, r -> {
            long headroom = r.nextLong(0, 1_000_000);
            long a = Long.MAX_VALUE - headroom;
            long b = headroom + r.nextLong(1, 1_000_000);
            return new Object[] {a, b};
        });
    }

    @ParameterizedTest
    @MethodSource("triples")
    void plusIsAssociative(long a, long b, long c) {
        Won x = Won.of(a), y = Won.of(b), z = Won.of(c);
        assertThat(x.plus(y).plus(z)).isEqualTo(x.plus(y.plus(z)));
    }

    @ParameterizedTest
    @MethodSource("triples")
    void plusIsCommutative(long a, long b, long ignored) {
        assertThat(Won.of(a).plus(Won.of(b))).isEqualTo(Won.of(b).plus(Won.of(a)));
    }

    @ParameterizedTest
    @MethodSource("triples")
    void minusUndoesPlusAndEqualsPlusNegation(long a, long b, long ignored) {
        Won x = Won.of(a), y = Won.of(b);
        assertThat(x.plus(y).minus(y)).isEqualTo(x);
        assertThat(x.minus(y)).isEqualTo(x.plus(y.negate()));
        assertThat(x.minus(x)).isEqualTo(Won.ZERO);
    }

    @ParameterizedTest
    @MethodSource("overflowingPairs")
    void plusOverflowThrows(long a, long b) {
        assertThatThrownBy(() -> Won.of(a).plus(Won.of(b))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Won.of(-a - 1).minus(Won.of(b))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void negateOfMinValueOverflows() {
        assertThatThrownBy(() -> Won.of(Long.MIN_VALUE).negate()).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void signAndComparison() {
        assertThat(Won.of(-1).isNegative()).isTrue();
        assertThat(Won.ZERO.isNegative()).isFalse();
        assertThat(Won.ZERO.isZero()).isTrue();
        assertThat(Won.of(10).isGreaterThan(Won.of(9))).isTrue();
        assertThat(Won.of(9).isLessThan(Won.of(10))).isTrue();
        assertThat(Won.of(0)).isSameAs(Won.ZERO);
    }

    @Test
    void hasNoMultiplicationOrDivision() {
        assertThat(Arrays.stream(Won.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName))
                .noneMatch(n -> n.startsWith("times") || n.startsWith("multiply") || n.startsWith("div")
                        || n.startsWith("mul") || n.startsWith("apply") || n.startsWith("scale"));
    }
}
