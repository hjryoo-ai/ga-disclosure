package com.ga.disclosure.api.dto;

/** 배정·해소 영수증(닫힌 모양 — 멱등 재생 튜플): 플래그 ID·유형·상태(OPEN/RESOLVED). */
public record FlagReceipt(String flagId, String type, String status) {
}
