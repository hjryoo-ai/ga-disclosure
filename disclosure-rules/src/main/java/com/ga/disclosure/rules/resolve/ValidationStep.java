package com.ga.disclosure.rules.resolve;

import com.ga.disclosure.domain.enums.ValidationStage;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 룰 본문 {@code validations[]}의 항목: 규칙 ID와 그 규칙을 실행하는 단계(설계서 §6.2, 기본값 없음 — 빈 단계는 스키마 위반).
 */
public record ValidationStep(String id, Set<ValidationStage> stages) {

    public ValidationStep {
        Objects.requireNonNull(id, "id");
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("validation " + id + " has no stages");
        }
        stages = Collections.unmodifiableSet(EnumSet.copyOf(stages));
    }

    public boolean runsIn(ValidationStage stage) {
        return stages.contains(stage);
    }
}
