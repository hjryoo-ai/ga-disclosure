package com.ga.disclosure.api.dto;

/** 준법 플래그 한 건(6A 수용심사 §2 ①): ID·유형·상태(OPEN/RESOLVED)·열린 시각·대상 확인서(ID·번호 — 없거나 봉인 전이면 null). 세부는 6B 준법 큐. */
public record FlagView(String flagId, String type, String status, String raisedAt, String disclosureId, String disclosureNo) {
}
