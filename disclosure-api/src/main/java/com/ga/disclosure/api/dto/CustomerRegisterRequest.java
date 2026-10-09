package com.ga.disclosure.api.dto;

/**
 * 고객 등록 본문(6B §9.2): 이름 필수, 전화·생년월일 선택. 주민번호·주소·계좌 필드는 없다 — 모르는 필드는 앱 전체 규약으로 400(필드 이름만, 승인 R1).
 * {@code toString}은 값을 모두 가린다(ApiLayerRulesTest (f) — MVC TRACE가 인자를 찍어도 원문이 남지 않는다).
 */
public record CustomerRegisterRequest(String name, String phone, String birthDate) {

    @Override
    public String toString() {
        return "CustomerRegisterRequest[name=" + (name == null ? "-" : "***") + ", phone=" + (phone == null ? "-" : "***") + ", birthDate="
                + (birthDate == null ? "-" : "***") + "]";
    }
}
