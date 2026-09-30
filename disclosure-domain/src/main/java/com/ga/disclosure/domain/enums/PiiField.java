package com.ga.disclosure.domain.enums;

/**
 * 고객 참조의 개인정보 항목(설계서 §5 {@code customer_ref}, §9). 이 셋 외의 고객 개인정보는 받지 않는다(CLAUDE.md 절대 규칙 6).
 * 마스킹 규칙(룰 데이터 {@code masking})과 암호문 AAD의 컬럼명이 이 어휘로 묶인다.
 */
public enum PiiField {
    NAME("name", "name_enc"),
    PHONE("phone", "phone_enc"),
    BIRTH_DATE("birthDate", "birth_date_enc");

    private final String ruleKey;
    private final String column;

    PiiField(String ruleKey, String column) {
        this.ruleKey = ruleKey;
        this.column = column;
    }

    /** 룰 본문 {@code masking}의 키. */
    public String ruleKey() {
        return ruleKey;
    }

    /** {@code customer_ref}의 암호문 컬럼명. */
    public String column() {
        return column;
    }
}
