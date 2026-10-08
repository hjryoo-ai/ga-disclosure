package com.ga.disclosure.api.dto;

/** 봉인 영수증: 상태·확인서 번호·보존 기한 미정 여부. */
public record SealReceipt(String disclosureId, String status, String disclosureNo, boolean retentionPending) {
}
