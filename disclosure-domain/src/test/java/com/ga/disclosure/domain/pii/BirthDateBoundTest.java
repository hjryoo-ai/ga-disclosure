package com.ga.disclosure.domain.pii;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 6B §9.2: 고객 등록 API의 생년월일은 기준일(호출자가 준다)보다 늦을 수 없다. 거부 메시지에 입력값이 없다. */
class BirthDateBoundTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-09", "20261009", "1900-01-01", "1985-02-28"})
    void onOrBeforeTheBoundIsAccepted(String raw) {
        assertThat(BirthDate.matches(BirthDate.parse(raw, TODAY), raw)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-10", "20261010", "2999-01-01"})
    void afterTheBoundIsRejectedWithoutEchoingTheValue(String raw) {
        assertThatThrownBy(() -> BirthDate.parse(raw, TODAY)).isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(raw).doesNotContain(raw.replace("-", "")));
    }

    @Test
    void theUnboundedParseIsUnchanged() {
        assertThat(BirthDate.matches(BirthDate.parse("2999-01-01"), "2999-01-01")).isTrue();
        assertThatThrownBy(() -> BirthDate.parse("1985-02-30", TODAY)).isInstanceOf(RuntimeException.class);
    }
}
