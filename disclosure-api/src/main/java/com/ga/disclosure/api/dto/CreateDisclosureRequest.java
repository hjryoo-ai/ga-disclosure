package com.ga.disclosure.api.dto;

/**
 * 초안 생성: 고객 가명 참조·상품군·상담일(yyyy-MM-dd)·서식 종류(STANDARD|AUTO), (6B) 선택 청약번호(공백 없는 1~64자 — 계약 연결의 첫 매칭 키,
 * 작성 뒤 변경 불가). 설계사는 토큰 주체의 {@code identity_link}에서 정한다.
 */
public record CreateDisclosureRequest(String customerRef, String groupCode, String consultDate, String templateType, String applicationNo) {

    /** 청약번호를 로그·예외에 싣지 않는다. */
    @Override
    public String toString() {
        return "CreateDisclosureRequest[" + groupCode + ", " + consultDate + ", " + templateType + ", applicationNo=" + (applicationNo == null ? "-" : "***") + "]";
    }
}
