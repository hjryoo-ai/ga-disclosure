package com.ga.disclosure.workflow.sign;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 서명 기기 정보(V8 {@code signature.device}): 기기 지문(대리 서명 탐지의 키, 설계서 §6.5)과 사용자 에이전트. 둘 다 없을 수 있다. 감사에는 원값을
 * 남기지 않는다(탐지 플래그의 감사는 지표·값·임계치만).
 */
public record DeviceInfo(String fingerprintOrNull, String userAgentOrNull) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 저장 형식 안전 상한(규제 룰이 아니다). */
    public static final int MAX_LENGTH = 512;

    public DeviceInfo {
        check(fingerprintOrNull, "fingerprint");
        check(userAgentOrNull, "userAgent");
    }

    private static void check(String v, String name) {
        if (v != null && (v.isBlank() || v.length() > MAX_LENGTH)) {
            throw new IllegalArgumentException("device " + name + " must be 1.." + MAX_LENGTH + " characters");
        }
    }

    public ObjectNode toJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("fingerprint", fingerprintOrNull);
        n.put("userAgent", userAgentOrNull);
        return n;
    }

    public static DeviceInfo fromJson(JsonNode n) {
        JsonNode fp = n.get("fingerprint");
        JsonNode ua = n.get("userAgent");
        return new DeviceInfo(fp == null || fp.isNull() ? null : fp.asString(), ua == null || ua.isNull() ? null : ua.asString());
    }
}
