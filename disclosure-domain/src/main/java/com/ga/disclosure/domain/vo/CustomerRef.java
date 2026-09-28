package com.ga.disclosure.domain.vo;

/**
 * 고객 참조 키. 개인정보가 아닌 불투명 식별자다 — 이름·연락처·생년월일은 {@code customer_ref} 테이블의 암호화 컬럼에만 있다.
 */
public record CustomerRef(String value) {

    public CustomerRef {
        Patterns.require(Patterns.OPAQUE_CODE, value, "customer ref");
    }

    public static CustomerRef of(String value) {
        return new CustomerRef(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
