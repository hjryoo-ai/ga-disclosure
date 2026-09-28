package com.ga.disclosure.domain.enums;

/** 서명 채널(D-7). 채널별 활성 여부는 룰 데이터({@code channels}). CERTIFIED_ESIGN은 v2 예약. */
public enum SignatureChannel {
    TOUCH_PAD,
    REMOTE_LINK,
    PAPER_SCAN,
    CERTIFIED_ESIGN
}
