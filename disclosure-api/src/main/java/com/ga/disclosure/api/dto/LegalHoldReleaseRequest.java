package com.ga.disclosure.api.dto;

/** 보류 해제: 사유 코드는 룰 {@code legalHoldReleaseReasons}. */
public record LegalHoldReleaseRequest(String reasonCode) {
}
