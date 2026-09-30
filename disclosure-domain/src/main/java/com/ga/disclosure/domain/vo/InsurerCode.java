package com.ga.disclosure.domain.vo;

/** 보험사 코드(외부 코드 체계, 예: {@code INS-A}). 8자 이하, 대문자·숫자·하이픈 — 엔진 계약 1.2.0. */
public record InsurerCode(String value) {

    public InsurerCode {
        Patterns.require(Patterns.INSURER_CODE, value, "insurer code");
    }

    public static InsurerCode of(String value) {
        return new InsurerCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
