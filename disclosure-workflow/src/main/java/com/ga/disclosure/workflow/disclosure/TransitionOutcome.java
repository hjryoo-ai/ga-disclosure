package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.disclosure.DisclosureCommand;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.rules.validation.ValidationResult;

import java.util.List;
import java.util.Objects;

/**
 * 애그리게이트 명령의 결과. 표 밖 명령은 결과가 아니라 {@code IllegalTransition} 예외다.
 * <ul>
 *   <li>{@link Applied}: 적용됨. 단계 검증 결과 전체(오버라이드 가능한 실패 포함 — 플래그·감사 대상)를 싣는다.</li>
 *   <li>{@link Rejected}: 단계 검증에 막는 실패(오버라이드 불가)가 있어 아무것도 바뀌지 않았다 — 업무 거부(커밋·감사).</li>
 * </ul>
 */
public sealed interface TransitionOutcome {

    DisclosureCommand command();

    DisclosureStatus from();

    /** 검증한 단계. DRAFT에서의 항목 교체처럼 검증이 없는 명령이면 {@code null}. */
    ValidationStage stage();

    List<ValidationResult> results();

    record Applied(DisclosureCommand command, DisclosureStatus from, DisclosureStatus to, ValidationStage stage,
                   List<ValidationResult> results) implements TransitionOutcome {
        public Applied {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            results = List.copyOf(results);
        }

        /** 전이를 막지 않은 오버라이드 가능한 실패(중간 단계는 플래그·감사만, 3A 계획 Q2). */
        public List<ValidationResult> overridableFailures() {
            return results.stream().filter(ValidationResult::overridable).toList();
        }
    }

    record Rejected(DisclosureCommand command, DisclosureStatus from, ValidationStage stage, List<ValidationResult> results)
            implements TransitionOutcome {
        public Rejected {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(stage, "stage");
            results = List.copyOf(results);
            if (results.stream().noneMatch(ValidationResult::blocking)) {
                throw new IllegalArgumentException("a rejection names at least one blocking failure");
            }
        }

        public List<ValidationResult> blocking() {
            return results.stream().filter(ValidationResult::blocking).toList();
        }
    }
}
