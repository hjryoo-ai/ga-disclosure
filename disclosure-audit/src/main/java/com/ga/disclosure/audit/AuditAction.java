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
    CUSTOMER_KEY_DESTROY,

    // ---------------------------------------------------------------- Phase 3A: 확인서 워크플로(대상 DISCLOSURE)
    /** 초안 생성: 상담일로 해석한 룰·서식 버전을 고정(detail에 버전 ID·본문 해시). */
    DISCLOSURE_CREATE,
    /** 단계 검증 1회: 단계와 규칙별 결과(통과·오버라이드 가능·대상 해시·메시지). 전이 명령·드라이런 모두. */
    DISCLOSURE_VALIDATE,
    /** 명령이 적용되어 상태가 정해졌다(from → to, 명령별 부수 효과: 스냅샷·사유 폐기 등). */
    DISCLOSURE_TRANSITION,
    /** 업무 거부: 명령이 적용되지 않았다(검증 차단·엔진 응답 거부·산출 중 항목 변경). 상태 불변, 트랜잭션은 커밋. */
    DISCLOSURE_REJECT,
    /** 엔진 등급·순위 호출 1회의 결과(수락·거부·노후, 스냅샷 ID·위반 목록·요청 지문). 오류 응답은 COMMAND_FAILED. */
    GRADE_FETCH,
    /** 관리자 예외 승인 기록(규칙·대상 해시·승인 ID — 사유 텍스트는 review 행에만). */
    EXCEPTION_APPROVE,
    /** 명령 오류: 업무 트랜잭션이 롤백된 뒤 같은 테넌트의 별도 트랜잭션으로 남기는 실패 사실(명령·예외 종류·오류 코드, 메시지 없음). */
    COMMAND_FAILED
}
