package com.ga.disclosure.api.dto;

/** 쓰기 영수증(닫힌 모양 — ID·상태만): 생성·항목·비교·산출·추천사유·재기준. */
public record DisclosureReceipt(String disclosureId, String status) {
}
