package com.ga.disclosure.api.dto;

/**
 * 세션 발급 결과. {@code deviceToken}은 현장 기기 채널(TOUCH_PAD·PAPER_SCAN)만 — 원격 링크 토큰은 통지 대기열이 발송 때 만든다(돌려주지 않는다).
 * 응답은 {@code Cache-Control: no-store}이고 멱등 재생하지 않는다(일회용 자격을 저장하지 않는다 — 재요청은 409 {@code IDEMPOTENCY_NOT_REPLAYABLE}).
 */
public record SessionIssueReceipt(String sessionId, String disclosureId, String channel, String expiresAt, String deviceToken) {
    /** 토큰·생체 서명·기기 지문은 싣지 않는다 — 프레임워크 TRACE 로그가 인자·반환값을 {@code toString}으로 찍는다(6A G6). */
    @Override
    public String toString() {
        return "SessionIssueReceipt[sessionId=" + sessionId + ", disclosureId=" + disclosureId + ", channel=" + channel + ", expiresAt=" + expiresAt
                + ", deviceToken=<redacted>]";
    }
}
