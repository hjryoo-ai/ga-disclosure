package com.ga.disclosure.domain.vo;

/** 유사상품군 코드(외부 코드 체계, 예: {@code PG-HEALTH-SIMPLE-NR}). 체계의 정본은 §14 #1. */
public record GroupCode(String value) {

    public GroupCode {
        Patterns.require(Patterns.UPPER_CODE, value, "group code");
    }

    public static GroupCode of(String value) {
        return new GroupCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
