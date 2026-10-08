package com.ga.disclosure.api.dto;

import tools.jackson.databind.JsonNode;

/** 고객 서명: 스트로크(JSON)·이미지(PNG base64)·기기 지문(선택). */
public record PublicCaptureRequest(String token, JsonNode strokes, String imagePngBase64, String deviceFingerprint) {
}
