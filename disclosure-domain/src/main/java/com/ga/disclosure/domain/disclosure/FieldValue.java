package com.ga.disclosure.domain.disclosure;

import java.util.Objects;

/**
 * 서식 항목값 1개(설계서 §5 {@code disclosure_item.field_values}의 {@code {code: {value, origin}}}). 값은 JSON(문자열·정수·불리언·
 * 배열·객체 — 해약환급예시 표 등)이며 도메인은 형식을 해석하지 않는다. {@code canonicalJson}은 RFC 8785 정규형 텍스트다.
 *
 * @param origin 값이 어디서 왔는가: 카탈로그 기본값({@code defaults}, 항목 코드가 키) 또는 설계사 입력
 */
public record FieldValue(String canonicalJson, Origin origin) {

    public enum Origin {
        CATALOG,
        AGENT
    }

    public FieldValue {
        Objects.requireNonNull(canonicalJson, "canonicalJson");
        Objects.requireNonNull(origin, "origin");
        if (canonicalJson.isBlank()) {
            throw new IllegalArgumentException("field value must be JSON");
        }
    }
}
