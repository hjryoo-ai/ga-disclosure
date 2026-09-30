package com.ga.disclosure.domain.pii;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 P6: {@link BirthDate#matches}는 결과만 돌려준다. 형식이 틀린 입력도 예외 없이 false이고(메시지로 새지 않음),
 * 표준 출력·오류에 아무것도 쓰지 않는다. 상수 시간 비교({@code MessageDigest.isEqual}) 사용은 아키텍처 테스트가 강제한다.
 */
class IdentityMatchTest {

    private static final Sensitive<BirthDate> STORED = BirthDate.parse("1944-03-05");

    @ParameterizedTest
    @ValueSource(strings = {"1944-03-05", "19440305", " 1944-03-05 "})
    void matchingInputsInEitherFormat(String input) {
        assertThat(BirthDate.matches(STORED, input)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1944-03-04", "19440306", "1944-3-5", "440305", "1944/03/05", "", "????????", "abcdefgh", "1944-02-30"})
    void everythingElseIsFalseWithoutThrowing(String input) {
        assertThat(BirthDate.matches(STORED, input)).isFalse();
    }

    @Test
    void nullInputIsFalse() {
        assertThat(BirthDate.matches(STORED, null)).isFalse();
    }

    @Test
    void matchingWritesNothingToStandardStreams() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(sink);
            System.setErr(sink);
            BirthDate.matches(STORED, "1944-03-05");
            BirthDate.matches(STORED, "not-a-date");
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
