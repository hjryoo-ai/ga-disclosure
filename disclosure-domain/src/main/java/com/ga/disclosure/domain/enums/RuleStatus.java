package com.ga.disclosure.domain.enums;

/**
 * 룰 버전 상태(§6.8 룰 승인 흐름). DRAFT→APPROVED→ACTIVE→RETIRED 한 단계 전진만(V4 트리거). 기준일에 시행 중이었던 룰(ACTIVE·
 * RETIRED)끼리는 같은 scope에서 기간이 겹칠 수 없다(DB 배타 제약).
 */
public enum RuleStatus {
    DRAFT,
    APPROVED,
    ACTIVE,
    RETIRED
}
