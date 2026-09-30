package com.ga.disclosure.domain.pii;

import com.ga.disclosure.domain.enums.PiiField;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.function.Function;

/**
 * 개인정보 봉투. 원문은 {@link #reveal(Function)}로만 꺼낼 수 있다.
 * <ul>
 *   <li>{@code toString()}은 항목 종류만 보이고 값은 전부 가린다(부분 마스킹은 룰 데이터 {@code masking}의 일이다).</li>
 *   <li>{@code equals}는 정규화된 원문 바이트를 {@link MessageDigest#isEqual}로 비교한다(상수 시간). {@code hashCode}는 항목 종류만 쓴다.</li>
 *   <li>{@link java.io.Serializable}이 아니고 게터가 없다 — Jackson 기본 매퍼는 빈 빈으로 실패하고, 앱 매퍼는 명시적으로 거부한다.</li>
 *   <li>{@code record}가 아니다(자동 {@code toString}·접근자가 원문을 드러낸다).</li>
 * </ul>
 */
public final class Sensitive<T extends SensitiveValue> {

    private final T value;

    private Sensitive(T value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    static <T extends SensitiveValue> Sensitive<T> of(T value) {
        return new Sensitive<>(value);
    }

    public PiiField field() {
        return value.field();
    }

    /** 원문에 함수를 적용한다. 호출처는 아키텍처 테스트의 허용 목록(암호화 어댑터·마스킹·본인확인 대조)뿐이다. */
    public <R> R reveal(Function<? super T, ? extends R> use) {
        return use.apply(value);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Sensitive<?> other) || other.value.field() != value.field()) {
            return false;
        }
        return MessageDigest.isEqual(value.canonical().getBytes(StandardCharsets.UTF_8),
                other.value.canonical().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int hashCode() {
        return value.field().hashCode();
    }

    @Override
    public String toString() {
        return "Sensitive[" + value.field() + ":****]";
    }
}
