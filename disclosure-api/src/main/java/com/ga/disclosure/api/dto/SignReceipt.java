package com.ga.disclosure.api.dto;

/** 서명·확인·검토·완료 영수증: 상태, 서명 ID(서명이 생겼을 때), 완료 여부, 보존 기한 미정 여부. */
public record SignReceipt(String disclosureId, String status, String signatureId, boolean completed, boolean retentionPending) {
}
