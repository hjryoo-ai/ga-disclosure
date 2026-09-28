package com.ga.disclosure.domain.vo;

/** 보험사 코드(외부 코드 체계, 예: {@code INS-A}). */
public record InsurerCode(String value) {

    public InsurerCode {
        Patterns.require(Patterns.UPPER_CODE, value, "insurer code");
    }

    public static InsurerCode of(String value) {
        return new InsurerCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
