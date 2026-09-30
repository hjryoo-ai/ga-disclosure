package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.rules.validation.ValidationResult;

import java.util.List;
import java.util.Objects;

/**
 * 유스케이스 결과. 적용이든 업무 거부든 트랜잭션은 커밋되고 감사가 남는다. 거부 사유 코드는 닫힌 어휘다.
 *
 * @param rejectionOrNull {@code VALIDATION_BLOCKED}(단계 검증 차단), {@code GRADE_REJECTED}(엔진 응답 스키마·정합성 위반),
 *                        {@code GRADE_STALE}(산출 중 항목 변경), 적용이면 {@code null}
 */
public record CommandResult(DisclosureId id, DisclosureStatus status, Rejection rejectionOrNull, List<ValidationResult> results,
                            List<String> engineViolations) {

    public enum Rejection {
        VALIDATION_BLOCKED,
        GRADE_REJECTED,
        GRADE_STALE
    }

    public CommandResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        results = List.copyOf(results);
        engineViolations = List.copyOf(engineViolations);
    }

    public boolean applied() {
        return rejectionOrNull == null;
    }
}
