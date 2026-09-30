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
    RULE_RECONCILE,
    /** 대상이 있는 준법 플래그를 올림(같은 대상의 열린 플래그가 있으면 새 행 없이 그 플래그, Phase 2). */
    FLAG_RAISE,
    /** 카탈로그 파일 수입(상품군·상품·패널) 또는 같은 파일 재수입 no-op(detail.outcome=NOOP). */
    CATALOG_IMPORT,
    /** 고객 참조 등록(암호문만 저장). detail에 개인정보 없음. */
    CUSTOMER_REGISTER,
    /** 고객 참조 복호화 조회. */
    CUSTOMER_VIEW,
    /** 원격 서명 링크 발송용 연락처 복호화(사유·참조 ID를 detail에). */
    CUSTOMER_PHONE_READ,
    /** 고객 데이터 키 순환: 활성 키 RETIRED, 새 키 ACTIVE. */
    CUSTOMER_KEY_ROTATE,
    /** 구 키 행을 새 키로 재암호화(배치 1회). */
    CUSTOMER_REKEY,
    /** 쓰는 행이 없어진 구 키의 키 재료 파기(DESTROYED). */
    CUSTOMER_KEY_DESTROY
}
