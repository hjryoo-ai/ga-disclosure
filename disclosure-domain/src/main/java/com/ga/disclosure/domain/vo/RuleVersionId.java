package com.ga.disclosure.domain.vo;

/** 룰 버전 식별자(예: {@code DISC-2026-07}). 봉인 시 확인서에 박제된다. */
public record RuleVersionId(String value) {

    public RuleVersionId {
        Patterns.require(Patterns.UPPER_CODE, value, "rule version id");
    }

    public static RuleVersionId of(String value) {
        return new RuleVersionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
