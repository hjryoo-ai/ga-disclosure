package com.ga.disclosure.domain.pii;

import com.ga.disclosure.domain.enums.PiiField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 2 P5(도메인 부분): 원문은 toString·예외 메시지·Java 직렬화 어디로도 나오지 않는다. equals는 정규형 비교. */
class SensitiveTest {

    private static final String NAME = "도메인시험ZQW";
    private static final String PHONE = "010-5550-0101";
    private static final String BIRTH = "1944-03-05";

    @Test
    void toStringHidesEveryValue() {
        List<Sensitive<?>> values = List.of(CustomerName.of(NAME), PhoneNumber.of(PHONE), BirthDate.parse(BIRTH));
        for (Sensitive<?> v : values) {
            String printed = v + " " + String.valueOf(v) + " " + List.of(v) + " " + v.reveal(Object::toString);
            assertThat(printed).doesNotContain("도메인", "ZQW", "5550", "0101", "1944", "0305");
        }
        assertThat(CustomerName.of(NAME).toString()).isEqualTo("Sensitive[NAME:****]");
    }

    @Test
    void equalityUsesTheNormalizedValue() {
        assertThat(PhoneNumber.of("010-5550-0101")).isEqualTo(PhoneNumber.of("01055500101"))
                .hasSameHashCodeAs(PhoneNumber.of("01099998888"));
        assertThat(CustomerName.of("  홍길동 ")).isEqualTo(CustomerName.of("홍길동")).isNotEqualTo(CustomerName.of("홍길순"));
        // NFD로 들어온 한글도 NFC로 맞춘다
        assertThat(CustomerName.of("홍")).isEqualTo(CustomerName.of("홍"));
        assertThat(BirthDate.parse("19440305")).isEqualTo(BirthDate.of(LocalDate.of(1944, 3, 5)));
        assertThat(BirthDate.parse("1944-03-05")).isNotEqualTo(CustomerName.of("1944-03-05"));
        assertThat(CustomerName.of(NAME).field()).isEqualTo(PiiField.NAME);
    }

    @Test
    void javaSerializationIsImpossible() {
        assertThatThrownBy(() -> new ObjectOutputStream(new ByteArrayOutputStream()).writeObject(CustomerName.of(NAME)))
                .isInstanceOf(NotSerializableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "02-123-4567", "010-12-34", "abc01055500101", "0105550010112"})
    void invalidPhoneMessagesDoNotEchoTheInput(String raw) {
        assertThatThrownBy(() -> PhoneNumber.of(raw)).isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(raw.isBlank() ? "\u0000" : raw));
    }

    @Test
    void invalidNameAndBirthDateMessagesDoNotEchoTheInput() {
        String secret = "비밀QZX" + "\u0007";
        assertThatThrownBy(() -> CustomerName.of(secret)).hasMessageNotContaining("비밀QZX");
        assertThatThrownBy(() -> CustomerName.of("가".repeat(101))).hasMessageNotContaining("가가");
        assertThatThrownBy(() -> BirthDate.parse("1944-02-30")).hasMessageNotContaining("1944");
        assertThatThrownBy(() -> BirthDate.parse("18991231")).hasMessageNotContaining("1899");
    }
}
