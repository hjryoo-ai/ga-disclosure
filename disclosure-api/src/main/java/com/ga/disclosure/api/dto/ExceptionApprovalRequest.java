package com.ga.disclosure.api.dto;

/** 예외 승인: 규칙 ID, 실패 대상 해시(SHA-256 소문자 hex — 검증 결과의 대상), 승인 사유. */
public record ExceptionApprovalRequest(String ruleId, String subjectHash, String reason) {
}
