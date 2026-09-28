package com.ga.disclosure.domain.grade;

import java.util.Objects;

/**
 * 엔진이 준 "유사상품군 평균 대비 비율"({@code ratioToAvg})의 <b>불투명 문자열</b>(설계서 §4.1).
 *
 * <p>숫자 변환·비교·정렬·정규화를 제공하지 않으며 {@link Comparable}도 아니다(CLAUDE.md 절대 규칙 1). 형식도 검사하지 않는다 —
 * {@code "0.84"}인지 {@code "84%"}인지를 우리가 안다고 가정하는 것 자체가 규칙 위반이다. 원문 보존이 봉인 해시 재현의 전제다.
 * {@link #value()} 호출은 봉인 렌더러와 API DTO 매퍼 패키지에서만 허용된다(아키텍처 테스트).
 */
public record RatioLabel(String value) {

    public RatioLabel {
        Objects.requireNonNull(value, "value");
    }
}
