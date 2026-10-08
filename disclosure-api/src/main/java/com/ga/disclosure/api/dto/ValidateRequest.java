package com.ga.disclosure.api.dto;

/** 검증 미리보기 단계(COMPARE|GRADE|REASON|SEAL). */
public record ValidateRequest(String stage) {
}
