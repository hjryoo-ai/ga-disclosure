package com.ga.disclosure.api.dto;

/** 열람 기록: 끝까지 스크롤했는가, 머문 초(서버 시각으로 1회 기록). 토큰은 헤더나 {@code token}. */
public record PublicViewRequest(String token, Boolean scrollComplete, Integer viewSeconds) {
    /** 토큰·생체 서명·기기 지문은 싣지 않는다 — 프레임워크 TRACE 로그가 인자·반환값을 {@code toString}으로 찍는다(6A G6). */
    @Override
    public String toString() {
        return "PublicViewRequest[token=<redacted>, scrollComplete=" + scrollComplete + ", viewSeconds=" + viewSeconds + "]";
    }
}
