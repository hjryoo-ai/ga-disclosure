package com.ga.disclosure.api.dto;

/** 본인확인 입력(룰이 요구하는 수단만 — 생년월일). 입력값은 유스케이스 밖으로 나가지 않는다. */
public record PublicVerifyRequest(String token, String birthDate) {

    @Override
    public String toString() {
        return "PublicVerifyRequest[redacted]";
    }
}
