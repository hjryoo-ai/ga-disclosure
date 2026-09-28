package com.ga.platform.core.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeededCasesTest {

    @Test
    void sameSeedProducesSameCases() {
        List<List<Object>> first = values(SeededCases.of(42L, 50, r -> new Object[] {r.nextLong(), r.nextInt(10)}).toList());
        List<List<Object>> second = values(SeededCases.of(42L, 50, r -> new Object[] {r.nextLong(), r.nextInt(10)}).toList());
        List<List<Object>> other = values(SeededCases.of(43L, 50, r -> new Object[] {r.nextLong(), r.nextInt(10)}).toList());
        assertThat(first).hasSize(50).isEqualTo(second).isNotEqualTo(other);
    }

    @Test
    void defaultCountIs500AndNamesCarrySeedAndIndex() {
        List<Arguments> cases = SeededCases.of(0xABCL, r -> new Object[] {r.nextInt()}).toList();
        assertThat(cases).hasSize(SeededCases.DEFAULT_COUNT);
        Arguments.ArgumentSet set = (Arguments.ArgumentSet) cases.get(7);
        assertThat(set.getName()).isEqualTo("seed=0x" + Long.toHexString(SeededCases.effectiveSeed(0xABCL)).toUpperCase() + ", case=7");
    }

    @Test
    void rejectsNonPositiveCount() {
        assertThatThrownBy(() -> SeededCases.of(1L, 0, r -> new Object[0])).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<List<Object>> values(List<Arguments> arguments) {
        return arguments.stream().map(a -> Arrays.asList(a.get())).toList();
    }
}
