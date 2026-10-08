package com.ga.disclosure.api.dto;

/** 확인서 목록 한 행(본문 없음, 고객은 가명 참조만). 봉인 전이면 번호·봉인 시각이 {@code null}. */
public record DisclosureSummary(String disclosureId, String disclosureNo, int version, String status, String agentId, String customerRef,
                                String groupCode, String consultDate, String sealedAt, String destroyedAt) {
}
