package com.ga.disclosure.api.dto;

/** 피드 ack: 이 seq까지 처리했다(이하 전부). */
public record EventAckRequest(Long upToSeq) {
}
