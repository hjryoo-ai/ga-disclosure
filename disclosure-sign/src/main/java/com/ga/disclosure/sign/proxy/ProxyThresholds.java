package com.ga.disclosure.sign.proxy;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * 대리 서명 탐지 임계치(룰 {@code proxySignatureDetection}). IP 지표는 선택 — 없으면 끈다(이동통신 NAT로 고객이 IP를 공유한다, 4 계획 승인 Q8).
 */
public record ProxyThresholds(int sameDeviceDistinctCustomersPerDay, OptionalInt sameIpDistinctCustomersPerDay, int minSecondsFromSendToSign) {

    public ProxyThresholds {
        Objects.requireNonNull(sameIpDistinctCustomersPerDay, "sameIpDistinctCustomersPerDay");
        if (sameDeviceDistinctCustomersPerDay < 2 || sameIpDistinctCustomersPerDay.orElse(2) < 2) {
            throw new IllegalArgumentException("distinct-customer thresholds must be at least 2 (one customer is always present)");
        }
        if (minSecondsFromSendToSign < 0) {
            throw new IllegalArgumentException("minSecondsFromSendToSign < 0");
        }
    }
}
