package com.ga.disclosure.rules.validation;

import java.util.Objects;

/**
 * 검증 규칙 1건의 결과.
 *
 * @param overridable 실패했지만 관리자 승인 + 준법 플래그로 넘어갈 수 있는가(설계서 §6.2 "오버라이드 경로만")
 */
public record ValidationResult(String ruleId, boolean passed, String message, boolean overridable) {

    public ValidationResult {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(message, "message");
        if (passed && overridable) {
            throw new IllegalArgumentException("a passed result is not overridable: " + ruleId);
        }
    }

    public static ValidationResult pass(String ruleId, String message) {
        return new ValidationResult(ruleId, true, message, false);
    }

    public static ValidationResult fail(String ruleId, String message) {
        return new ValidationResult(ruleId, false, message, false);
    }

    public static ValidationResult failOverridable(String ruleId, String message) {
        return new ValidationResult(ruleId, false, message, true);
    }
}
