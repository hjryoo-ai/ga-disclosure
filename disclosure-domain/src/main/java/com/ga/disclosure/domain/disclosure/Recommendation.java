package com.ga.disclosure.domain.disclosure;

import com.ga.disclosure.domain.vo.ReasonCode;

import java.util.List;
import java.util.Optional;

/**
 * 항목 1건의 추천사유(설계서 §5 {@code recommendation}). 코드는 설계사가 고른 것과 룰 데이터가 {@code auto=true}로 정한 자동 부가
 * 코드(고객 요청 표시)뿐이다. 텍스트는 설계사 입력만 — 시스템이 채우지 않는다(CLAUDE.md 절대 규칙 7). 코드 허용 여부는 룰 검증
 * (R-REASON·R-REQUESTED)이 판단한다.
 */
public record Recommendation(List<ReasonCode> codes, String textOrNull) {

    public Recommendation {
        codes = List.copyOf(codes);
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("a recommendation carries at least one reason code");
        }
        if (codes.stream().distinct().count() != codes.size()) {
            throw new IllegalArgumentException("duplicate reason code");
        }
        if (textOrNull != null && textOrNull.isBlank()) {
            textOrNull = null;
        }
    }

    public Optional<String> text() {
        return Optional.ofNullable(textOrNull);
    }
}
