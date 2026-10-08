package com.ga.disclosure.api.dto;

/** 예외 승인 영수증: 승인 ID·확인서·규칙. */
public record ExceptionApprovalReceipt(String reviewId, String disclosureId, String ruleId) {
}
