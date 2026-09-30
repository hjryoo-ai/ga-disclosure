package com.ga.disclosure.domain.enums;

/**
 * 검증 규칙을 실행하는 단계(설계서 §6.1 전이와 1:1, §6.2). 각 규칙이 어느 단계에서 도는지는 룰 데이터
 * {@code validations[].stages}가 정하고, 이 열거형은 워크플로 코드가 분기하는 닫힌 어휘다(Phase 1 수용 심사 §3-1).
 * SEAL은 이전 단계 규칙을 다시 도는 방어적 재검증 단계다.
 */
public enum ValidationStage {
    /** DRAFT → COMPARED */
    COMPARE,
    /** COMPARED → GRADED */
    GRADE,
    /** GRADED → REASONED */
    REASON,
    /** REASONED → SEALED */
    SEAL,
    /** PARTIALLY_SIGNED → COMPLETED */
    COMPLETE
}
