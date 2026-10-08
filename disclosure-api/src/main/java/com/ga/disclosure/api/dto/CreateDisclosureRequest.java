package com.ga.disclosure.api.dto;

/** 초안 생성: 고객 가명 참조·상품군·상담일(yyyy-MM-dd)·서식 종류(STANDARD|AUTO). 설계사는 토큰 주체의 {@code identity_link}에서 정한다. */
public record CreateDisclosureRequest(String customerRef, String groupCode, String consultDate, String templateType) {
}
