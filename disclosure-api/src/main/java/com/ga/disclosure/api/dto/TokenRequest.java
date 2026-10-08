package com.ga.disclosure.api.dto;

/** 현장 기기 세션 토큰(대면 확인). 토큰은 본문으로만 받는다(경로·질의에 두지 않는다). */
public record TokenRequest(String token) {
}
