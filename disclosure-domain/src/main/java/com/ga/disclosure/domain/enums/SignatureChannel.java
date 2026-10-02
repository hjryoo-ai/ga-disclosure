package com.ga.disclosure.domain.enums;

/**
 * 서명 채널 = 서명자에게 도달한 경로(D-7, 4 계획 승인 Q3). 고객 채널(TOUCH_PAD·REMOTE_LINK·PAPER_SCAN·CERTIFIED_ESIGN)의 활성 여부는 룰
 * 데이터({@code channels})이고, {@link #SSO}는 설계사·관리자가 사내 인증(OIDC)으로 서명하는 경로라 룰 {@code channels}에 없다.
 * 행위는 {@link SignatureMethod}. CERTIFIED_ESIGN은 v2 예약.
 */
public enum SignatureChannel {
    TOUCH_PAD,
    REMOTE_LINK,
    PAPER_SCAN,
    CERTIFIED_ESIGN,
    SSO;

    /** 고객 서명 채널(룰 {@code channels} 키, 서명 세션을 거친다). */
    public boolean isCustomerChannel() {
        return this != SSO;
    }
}
