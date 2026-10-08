package com.ga.disclosure.api.dto;

/** 공개 경로의 서명 토큰 원문 — 게이트가 헤더 {@code X-Sign-Token} 또는 본문 {@code token}에서 꺼내 형식·테넌트·한도를 통과시킨 것. 로그·응답에 싣지 않는다. */
public record PublicToken(String raw) {

    @Override
    public String toString() {
        return "PublicToken[redacted]";
    }
}
