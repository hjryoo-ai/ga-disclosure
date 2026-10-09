package com.ga.disclosure.api.dto;

/** 컨트롤러 인자: 이 요청의 {@code Idempotency-Key}(인터셉터가 형식을 이미 확인했다). 원 헤더 읽기는 {@code api.security}만 한다. */
public record IdempotencyKey(String value) {
}
