package com.ga.disclosure.api.dto;

/** 요청을 보낸 단말의 주소·User-Agent(서명 증거 — 설계사 서명). {@code api.security}의 인자 해석기가 채운다(원 헤더 읽기는 그 패키지뿐). */
public record ClientContext(String ipOrNull, String userAgentOrNull) {
}
