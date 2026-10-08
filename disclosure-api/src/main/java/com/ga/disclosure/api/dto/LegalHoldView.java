package com.ga.disclosure.api.dto;

/** 법적 보류 한 건. 사유는 코드만(텍스트는 개인정보 컬럼 — 싣지 않는다). 대상은 확인서 ID 또는 고객 가명 참조 중 하나. */
public record LegalHoldView(String holdId, String disclosureId, String customerRef, String reasonCode, String placedBy, String placedAt,
                            String releasedBy, String releasedAt, String releaseReasonCode) {
}
