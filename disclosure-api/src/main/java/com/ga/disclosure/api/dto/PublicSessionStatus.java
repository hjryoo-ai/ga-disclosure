package com.ga.disclosure.api.dto;

/** 열람 기록 결과: 세션 상태(열린 세션만 — 그 밖은 거부). */
public record PublicSessionStatus(String sessionStatus) {
}
