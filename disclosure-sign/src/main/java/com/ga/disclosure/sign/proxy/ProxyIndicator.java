package com.ga.disclosure.sign.proxy;

import java.util.Objects;

/**
 * 탐지된 지표 1건(감사 {@code FLAG_RAISE}에 지표·값·임계치만 — 기기 지문·IP 원값은 남기지 않는다).
 *
 * @param value     관측값(서로 다른 고객 수, 또는 발송→서명 초)
 * @param threshold 룰 임계치
 */
public record ProxyIndicator(Kind kind, long value, long threshold) {

    public enum Kind {
        /** 같은 기기 지문·같은 KST 날 서로 다른 고객 수 ≥ N. */
        SAME_DEVICE,
        /** 같은 IP·같은 KST 날 서로 다른 고객 수 ≥ N(선택 지표). */
        SAME_IP,
        /** 링크 발송 → 서명이 M초 미만. */
        FAST_SIGN
    }

    public ProxyIndicator {
        Objects.requireNonNull(kind, "kind");
    }
}
