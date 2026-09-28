package com.ga.platform.core.money;

/**
 * 표시 전용 비율(분자/분모 정수).
 *
 * <p>제공하는 연산은 {@link #toPercentString()} — 백분율 소수 1자리 문자열 — 하나뿐이다. 비율끼리의 비교·정렬,
 * 금액({@link Won})과의 곱셈은 제공하지 않는다. {@code Won}을 만들 수 있는 경로가 없다는 것이 이 타입의 계약이다.
 * 부동소수점을 쓰지 않고 정수 연산(반올림: 0에서 먼 쪽으로 0.05 올림)으로 문자열을 만든다.
 *
 * <p>동등성은 성분 기준이다: {@code Ratio(1, 2)}와 {@code Ratio(2, 4)}는 같지 않다(기약분수로 바꾸지 않는다).
 *
 * @param numerator   분자
 * @param denominator 분모(양수)
 */
public record Ratio(long numerator, long denominator) {

    public Ratio {
        if (denominator <= 0L) {
            throw new IllegalArgumentException("denominator must be positive");
        }
    }

    public static Ratio of(long numerator, long denominator) {
        return new Ratio(numerator, denominator);
    }

    /** 예: {@code Ratio(84, 100)} → {@code "84.0%"}, {@code Ratio(1, 3)} → {@code "33.3%"}, {@code Ratio(2, 3)} → {@code "66.7%"}. */
    public String toPercentString() {
        long scaled = Math.multiplyExact(Math.absExact(numerator), 1000L); // 백분율 × 10 (소수 1자리)
        long quotient = scaled / denominator;
        long remainder = scaled % denominator;
        if (remainder >= denominator - remainder) { // remainder * 2 >= denominator 의 오버플로 없는 형태
            quotient = Math.addExact(quotient, 1L);
        }
        String sign = numerator < 0L && quotient != 0L ? "-" : "";
        return sign + (quotient / 10L) + "." + (quotient % 10L) + "%";
    }

    @Override
    public String toString() {
        return toPercentString();
    }
}
