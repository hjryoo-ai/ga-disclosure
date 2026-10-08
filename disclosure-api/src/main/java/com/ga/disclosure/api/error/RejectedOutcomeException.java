package com.ga.disclosure.api.error;

import com.ga.disclosure.workflow.RejectionCategory;

import java.util.List;
import java.util.Objects;

/**
 * 유스케이스 결과(Outcome)에 담긴 업무 거부 — 매퍼가 던지고 advice가 범주로 상태를 정한다({@code CONFLICT → 409}, {@code INVALID → 422}, 6A 계획 §4.2).
 * 본문은 {@code REJECTED} + 거부 코드(와 규칙 ID)만.
 */
public final class RejectedOutcomeException extends RuntimeException {

    private final RejectionCategory category;
    private final List<Problem.Rejection> rejections;

    public RejectedOutcomeException(RejectionCategory category, List<Problem.Rejection> rejections) {
        super("rejected: " + rejections.stream().map(Problem.Rejection::code).toList(), null, false, false);
        this.category = Objects.requireNonNull(category, "category");
        this.rejections = List.copyOf(rejections);
    }

    public RejectionCategory category() {
        return category;
    }

    public List<Problem.Rejection> rejections() {
        return rejections;
    }
}
