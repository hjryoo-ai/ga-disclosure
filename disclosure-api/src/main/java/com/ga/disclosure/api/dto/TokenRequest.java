package com.ga.disclosure.api.dto;

/** 현장 기기 세션 토큰(대면 확인). 토큰은 본문으로만 받는다(경로·질의에 두지 않는다). */
public record TokenRequest(String token) {
    /** 토큰·생체 서명·기기 지문은 싣지 않는다 — 프레임워크 TRACE 로그가 인자·반환값을 {@code toString}으로 찍는다(6A G6). */
    @Override
    public String toString() {
        return "TokenRequest[token=<redacted>]";
    }
}
