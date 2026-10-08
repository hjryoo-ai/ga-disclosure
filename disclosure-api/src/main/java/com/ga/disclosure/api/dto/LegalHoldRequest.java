package com.ga.disclosure.api.dto;

/** 보류 설정: 대상은 {@code disclosureId} 또는 {@code customerRef} 중 정확히 하나. 사유 코드는 룰 {@code legalHoldReasons}. */
public record LegalHoldRequest(String disclosureId, String customerRef, String reasonCode, String reasonText) {
}
