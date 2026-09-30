package com.ga.disclosure.domain.disclosure;

/**
 * 확인서 상태를 바꾸는 명령(설계서 §6.1 상태 × 명령 표의 열). 어떤 상태에서 어떤 명령이 허용되고 결과가 무엇인지는
 * {@link DisclosureStateTable}이 정하고, 그 표의 정본은 설계서 §6.1의 {@code state-table} 블록이다(테스트가 대조한다).
 * 상담일 변경 명령은 없다 — 상담일이 다르면 새 초안을 만든다.
 */
public enum DisclosureCommand {
    /** 비교 항목 교체. 산출 이후면 스냅샷·추천사유를 버리고 COMPARED로 돌아간다. */
    REPLACE_ITEMS,
    /** DRAFT → COMPARED(비교 단계 검증). */
    COMPARE,
    /** 엔진 스냅샷 적용(첫 산출·재산출). 추천사유를 버리고 GRADED. */
    APPLY_SNAPSHOT,
    /** 추천사유 입력(설계사 입력만, CLAUDE.md 절대 규칙 7). */
    SET_RECOMMENDATIONS,
    /** 봉인(3B). */
    SEAL,
    /** 서명 1건 수집(Phase 4). */
    SIGN,
    /** 서명자 집합 충족 확인(Phase 4). */
    COMPLETE,
    /** 서명 기한 경과. */
    EXPIRE,
    /** 무효(사유·승인). */
    VOID,
    /** 정정(새 버전 생성, 3B). */
    SUPERSEDE
}
