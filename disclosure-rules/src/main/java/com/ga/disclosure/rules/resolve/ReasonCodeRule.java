package com.ga.disclosure.rules.resolve;

import com.ga.disclosure.domain.vo.ReasonCode;

import java.util.Objects;

/**
 * 룰 본문 {@code reasonCodes[]}의 항목.
 *
 * @param requiresText 이 코드를 고르면 추천사유 텍스트가 필수
 * @param auto         시스템이 조건에 따라 부가하는 코드(설계사 선택지가 아니다, R-REQUESTED)
 */
public record ReasonCodeRule(ReasonCode code, String label, boolean requiresText, boolean auto) {

    public ReasonCodeRule {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
    }
}
