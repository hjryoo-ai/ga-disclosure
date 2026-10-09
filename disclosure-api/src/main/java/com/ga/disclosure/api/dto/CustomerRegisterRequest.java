package com.ga.disclosure.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.Map;

/**
 * 고객 등록 본문(6B §9.2): 이름 필수, 전화·생년월일 선택. 주민번호·주소·계좌 필드는 없다 — 모르는 필드는 {@code unknown}에 모였다가 매퍼가 400으로
 * 거부한다(이 앱의 JSON 읽기는 모르는 필드를 무시하므로 이 경로만 받아서 거부한다). {@code toString}은 값을 모두 가린다 — 모르는 필드도 개수만
 * (ApiLayerRulesTest (f) — MVC TRACE가 인자를 찍어도 원문이 남지 않는다).
 */
public record CustomerRegisterRequest(String name, String phone, String birthDate, @JsonAnySetter Map<String, Object> unknown) {

    public CustomerRegisterRequest {
        unknown = unknown == null ? Map.of() : unknown;
    }

    @Override
    public String toString() {
        return "CustomerRegisterRequest[name=" + (name == null ? "-" : "***") + ", phone=" + (phone == null ? "-" : "***") + ", birthDate="
                + (birthDate == null ? "-" : "***") + ", unknown=" + unknown.size() + "]";
    }
}
