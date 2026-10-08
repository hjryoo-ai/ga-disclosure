package com.ga.disclosure.api.dto;

/** 보류 설정·해제 영수증(닫힌 모양 — 멱등 재생 튜플): 보류 ID와 저장소 보류 결과(객체 수만). */
public record LegalHoldReceipt(String holdId, int storageApplied, int storageFailed, boolean storageUnsupported) {
}
