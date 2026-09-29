package com.ga.disclosure.audit;

/**
 * 감사 행위(설계서 §5 {@code audit_log.action}). 시스템이 스스로 기록하는 닫힌 어휘이며 Phase가 진행되며 늘어난다
 * (CREATE·SEAL·SIGN·VIEW … 는 Phase 3 이후).
 */
public enum AuditAction {
    /** 규제 번들을 테넌트에 복제(또는 같은 번들 재실행 no-op). */
    RULE_DISTRIBUTE,
    /** TENANT 룰 DRAFT → APPROVED. */
    RULE_APPROVE,
    /** 활성화 배치: APPROVED → ACTIVE. */
    RULE_ACTIVATE,
    /** 활성화 배치: ACTIVE → RETIRED. */
    RULE_RETIRE,
    /** 번들 해시 대사 실행(불일치는 RULE_DRIFT 플래그). */
    RULE_RECONCILE
}
