package com.ga.platform.core.money;

/**
 * 정수 원(KRW) 금액.
 *
 * <p>덧셈·뺄셈·부호 반전·비교만 제공한다. <b>곱셈·나눗셈은 없다</b> — 요율을 곱해 금액을 만드는 연산은
 * 수수료 엔진의 책임이며 이 타입으로는 표현할 수 없다. 모든 연산은 {@link Math#addExact(long, long)} 계열을
 * 써서 오버플로 시 {@link ArithmeticException}을 던진다.
 *
 * @param value 원 단위 정수 금액(음수 허용: 환수·차감 표현용)
 */
public record Won(long value) implements Comparable<Won> {

    public static final Won ZERO = new Won(0L);

    public static Won of(long value) {
        return value == 0L ? ZERO : new Won(value);
    }

    public Won plus(Won other) {
        return of(Math.addExact(value, other.value));
    }

    public Won minus(Won other) {
        return of(Math.subtractExact(value, other.value));
    }

    public Won negate() {
        return of(Math.negateExact(value));
    }

    public boolean isNegative() {
        return value < 0L;
    }

    public boolean isZero() {
        return value == 0L;
    }

    public boolean isGreaterThan(Won other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Won other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Won other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value + "원";
    }
}
