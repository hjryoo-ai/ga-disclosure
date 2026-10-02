package com.ga.disclosure.sign.session;

/**
 * 세션 사건(설계서 §6.5 {@code session-state-table}). 열람·본인확인 통과·실패·서명 수집은 고객 경로, TTL 경과는 만료 배치·재발급 직전,
 * 재발급·문서 무효·정정·만료는 확인서 쪽에서 온다.
 */
public enum SessionEvent {
    OPEN_VIEW,
    IDENTITY_PASS,
    IDENTITY_FAIL,
    CAPTURE,
    TTL_ELAPSED,
    REISSUE,
    DOCUMENT_VOID,
    DOCUMENT_SUPERSEDE,
    DOCUMENT_EXPIRE
}
