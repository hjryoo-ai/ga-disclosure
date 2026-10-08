package com.ga.disclosure.api.dto;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 항목 전체 교체. 카탈로그 상품은 {@code productKey}, 임시등록은 {@code productKey} 없이 {@code insurerCode}·{@code productName}·{@code quoteDocNo}.
 * {@code agentValues}는 서식 항목 코드 → 설계사 입력 값(서식이 편집 가능으로 둔 항목만 — 유스케이스가 검사).
 */
public record ItemsRequest(List<Item> items) {

    /** {@code recommended}·{@code requestedByCustomer}는 생략하면 {@code false}. */
    public record Item(String productKey, String insurerCode, String productName, String quoteDocNo, Boolean recommended,
                       Boolean requestedByCustomer, Map<String, JsonNode> agentValues) {
    }
}
