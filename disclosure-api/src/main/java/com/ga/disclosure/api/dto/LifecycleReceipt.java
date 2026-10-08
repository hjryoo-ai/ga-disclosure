package com.ga.disclosure.api.dto;

/** 무효·정정·재기준 영수증: 상태와 새 버전 ID(정정·재기준). */
public record LifecycleReceipt(String disclosureId, String status, String newVersionId) {
}
