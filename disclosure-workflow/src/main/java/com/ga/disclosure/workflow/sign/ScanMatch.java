package com.ga.disclosure.workflow.sign;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 종이 스캔 대조 기록(V8 {@code signature.scan_match}): 설계사가 스캔본 각주에서 읽어 입력한 확인서 번호·해시 접두가 원본과 맞았는가(OCR 없음,
 * 4 계획 §7.2). 입력값 자체는 남기지 않는다 — 맞았을 때만 서명이 생기므로 기록되는 값은 언제나 일치다.
 */
public record ScanMatch(boolean disclosureNoMatched, boolean hashPrefixMatched, int hashPrefixLength) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public boolean matched() {
        return disclosureNoMatched && hashPrefixMatched;
    }

    public ObjectNode toJson() {
        return JSON.createObjectNode().put("disclosureNoMatched", disclosureNoMatched).put("hashPrefixMatched", hashPrefixMatched)
                .put("hashPrefixLength", hashPrefixLength);
    }

    public static ScanMatch fromJson(JsonNode n) {
        return new ScanMatch(n.get("disclosureNoMatched").booleanValue(), n.get("hashPrefixMatched").booleanValue(),
                n.get("hashPrefixLength").intValue());
    }
}
