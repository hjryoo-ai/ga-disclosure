package com.ga.disclosure.api.dto;

/** 고객 등록 응답(6B §9, 승인 §4 조건 2): 가명과 영수증 ID뿐 — 생성 여부를 싣지 않는다(첫 등록·재생·NOOP가 같은 바이트). */
public record CustomerReceipt(String customerRef, String receiptId) {
}
