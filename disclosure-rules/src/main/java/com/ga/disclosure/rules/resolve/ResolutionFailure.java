package com.ga.disclosure.rules.resolve;

/** 룰·서식 해석 실패 사유(fail-fast). 규칙 데이터가 아니라 해석기의 실패 분류다. */
public enum ResolutionFailure {
    /** 기준일에 시행 중인 GLOBAL 룰이 없다. */
    NO_GLOBAL_RULE,
    /** 한 scope(또는 서식 유형)에서 기준일에 2건 이상이 매칭됐다. */
    AMBIGUOUS,
    /** TENANT 룰이 GLOBAL의 {@code tenantOverridable}에 없는 키를 덮어쓰려 했다(조용히 무시하지 않는다). */
    DISALLOWED_OVERRIDE,
    /** 룰의 {@code validations}에 레지스트리에 없는 규칙 ID가 있다. */
    UNKNOWN_VALIDATION,
    /** 기준일에 적용할 서식이 없다. */
    NO_TEMPLATE,
    /** 확인서에 고정된 룰·서식 버전이 저장소에 없다(3A: 초안 생성 시 고정한 ID로만 로드한다). */
    PINNED_VERSION_MISSING,
    /**
     * 고정된 룰 버전이 상담일에 시행 중이었던 적이 없다 — 상태가 ACTIVE·RETIRED가 아니거나(DRAFT·APPROVED), scope가 다르거나, 시작일이 상담일
     * 뒤다(4 계획 승인 Q1 — 로더는 시작일만 본다, 끝은 봉인 조건이 본다).
     */
    PINNED_VERSION_NOT_IN_FORCE,
    /** 로드한 고정 룰의 본문 해시가 초안 고정(생성·재기준) 때 감사에 기록된 해시와 다르거나 기록이 없다(승인 Q1 — 불변 트리거의 이중 검사). */
    PINNED_BODY_MISMATCH
}
