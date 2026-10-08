package com.ga.disclosure.api.dto;

/** ack 결과: 이제 ack한 지점(기본 시작점)과 발행자의 마지막 seq. */
public record EventAckReceipt(long ackedSeq, long headSeq) {
}
