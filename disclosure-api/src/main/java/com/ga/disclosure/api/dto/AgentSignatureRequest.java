package com.ga.disclosure.api.dto;

import tools.jackson.databind.JsonNode;

/** 설계사 서명: DRAWN이면 스트로크(JSON)·이미지(PNG base64), SSO_APPROVAL이면 둘 다 없음(룰 {@code agentSignMethod}). 기기 지문은 선택. */
public record AgentSignatureRequest(JsonNode strokes, String imagePngBase64, String deviceFingerprint) {
}
