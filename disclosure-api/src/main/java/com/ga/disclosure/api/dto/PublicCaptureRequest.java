package com.ga.disclosure.api.dto;

import tools.jackson.databind.JsonNode;

/** 고객 서명: 스트로크(JSON)·이미지(PNG base64)·기기 지문(선택). */
public record PublicCaptureRequest(String token, JsonNode strokes, String imagePngBase64, String deviceFingerprint) {
    /** 토큰·생체 서명·기기 지문은 싣지 않는다 — 프레임워크 TRACE 로그가 인자·반환값을 {@code toString}으로 찍는다(6A G6). */
    @Override
    public String toString() {
        return "PublicCaptureRequest[token=<redacted>, strokes=<redacted>, imagePngBase64=<redacted>, deviceFingerprint=<redacted>]";
    }
}
