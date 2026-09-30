package com.ga.disclosure.rules.validation;

import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * 검증 규칙 1건의 결과.
 *
 * @param overridable 실패했지만 관리자 승인 + 준법 플래그로 넘어갈 수 있는가(설계서 §6.2 "오버라이드 경로만")
 * @param subject     오버라이드 가능한 실패가 지목한 대상(정규 JSON으로 해시할 값). 승인({@code review})은 이 대상의
 *                    {@link #subjectHash()}에 귀속된다 — 대상이 바뀌면 승인이 효력을 잃고, 같은 대상이면 재산출 뒤에도 유지된다
 *                    (3A 계획 Q3). 오버라이드 가능한 실패에만 있다.
 */
public record ValidationResult(String ruleId, boolean passed, String message, boolean overridable, JsonNode subject) {

    public ValidationResult {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(message, "message");
        if (passed && overridable) {
            throw new IllegalArgumentException("a passed result is not overridable: " + ruleId);
        }
        if (overridable != (subject != null)) {
            throw new IllegalArgumentException("an overridable failure names its subject, and only it does: " + ruleId);
        }
        subject = subject == null ? null : subject.deepCopy();
    }

    public static ValidationResult pass(String ruleId, String message) {
        return new ValidationResult(ruleId, true, message, false, null);
    }

    public static ValidationResult fail(String ruleId, String message) {
        return new ValidationResult(ruleId, false, message, false, null);
    }

    public static ValidationResult failOverridable(String ruleId, String message, JsonNode subject) {
        return new ValidationResult(ruleId, false, message, true, Objects.requireNonNull(subject, "subject"));
    }

    @Override
    public JsonNode subject() {
        return subject == null ? null : subject.deepCopy();
    }

    /** 오버라이드 대상의 {@code SHA-256(JCS(subject))}(소문자 hex). 오버라이드 가능한 실패가 아니면 비어 있다. */
    public Optional<String> subjectHash() {
        return subject == null ? Optional.empty() : Optional.of(Sha256.of(Canonicalizer.canonicalize(subject)));
    }

    /** 전이를 막는 실패인가(오버라이드 불가 실패). */
    public boolean blocking() {
        return !passed && !overridable;
    }
}
