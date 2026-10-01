package com.ga.disclosure.rules.template;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 결속 하나의 해석 결과. "없음"은 {@code Optional.empty()}이고, 있으면 셋 중 하나다.
 * <ul>
 *   <li>{@link Value}: 인쇄할 값(JSON — 문자열·정수·불리언·배열·객체, 표기는 로케일 없는 원문, 3B 계획 승인 Q10).</li>
 *   <li>{@link Blank}: 빈 칸이 곧 값이다(산출된 항목의 산출불가 칸, 비추천 항목의 추천사유 칸).</li>
 *   <li>{@link Pending}: 봉인이 채운다(확인서 번호·고객 성명) — 검증 시점에만 나오고 렌더러는 거부한다.</li>
 * </ul>
 */
public sealed interface Bound {

    record Value(JsonNode value) implements Bound {
        public Value {
            Objects.requireNonNull(value, "value");
            if (value.isNull() || value.isMissingNode() || (value.isString() && value.asString().isBlank())) {
                throw new IllegalArgumentException("a bound value is never null or blank");
            }
        }
    }

    record Blank() implements Bound {
    }

    record Pending() implements Bound {
    }
}
