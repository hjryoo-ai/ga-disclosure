package com.ga.disclosure.api.dto;

import java.util.List;

/** 추천사유(설계사 입력만 — 절대 규칙 7): 항목 번호별 사유 코드(룰 {@code reasonCodes})와 선택 텍스트. */
public record RecommendationsRequest(List<Reason> reasons) {

    public record Reason(int itemNo, List<String> codes, String text) {
    }
}
