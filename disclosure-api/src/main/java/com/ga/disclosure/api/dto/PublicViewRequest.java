package com.ga.disclosure.api.dto;

/** 열람 기록: 끝까지 스크롤했는가, 머문 초(서버 시각으로 1회 기록). 토큰은 헤더나 {@code token}. */
public record PublicViewRequest(String token, Boolean scrollComplete, Integer viewSeconds) {
}
