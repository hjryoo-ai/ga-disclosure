package com.ga.disclosure.domain.enums;

/** 룰 버전 상태(§6.8 룰 승인 흐름). ACTIVE끼리는 같은 scope에서 기간이 겹칠 수 없다(DB 배타 제약). */
public enum RuleStatus {
    DRAFT,
    APPROVED,
    ACTIVE,
    RETIRED
}
