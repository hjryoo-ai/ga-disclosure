package com.ga.disclosure.sign.proxy;

import com.ga.disclosure.domain.enums.SignatureChannel;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 대리 서명 탐지 산식(설계서 §6.5, 4 계획 §5). 대상은 REMOTE_LINK 고객 서명뿐이다 — TOUCH_PAD는 설계사 기기 1대로 여러 고객이 서명하는 것이
 * 정상이다. 탐지는 서명을 막지 않고 플래그만 올린다. "일"은 Asia/Seoul 달력일(재현 가능한 경계). 집계(같은 날 같은 지문·IP의 서로 다른
 * 고객 수, 이번 서명 포함)는 저장소가 하고 이 함수는 판정만 한다.
 */
public final class ProxySignatureDetector {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private ProxySignatureDetector() {
    }

    /** 집계 경계: 서명 시각의 KST 날짜. */
    public static LocalDate dayOf(Instant signedAt) {
        return signedAt.atZone(SEOUL).toLocalDate();
    }

    /**
     * @param sameDeviceCustomers 이번 서명의 기기 지문으로 같은 날 서명한 서로 다른 고객 수(이번 포함). 지문이 없으면 빈 값
     * @param sameIpCustomers     같은 식의 IP 지표. IP가 없으면 빈 값
     * @param sentAt              링크 발송 시각(없으면 발송→서명 지표를 계산하지 않는다)
     */
    public static List<ProxyIndicator> evaluate(SignatureChannel channel, OptionalInt sameDeviceCustomers, OptionalInt sameIpCustomers,
                                                Instant sentAt, Instant signedAt, ProxyThresholds thresholds) {
        Objects.requireNonNull(signedAt, "signedAt");
        if (channel != SignatureChannel.REMOTE_LINK) {
            return List.of();
        }
        List<ProxyIndicator> out = new ArrayList<>();
        if (sameDeviceCustomers.isPresent() && sameDeviceCustomers.getAsInt() >= thresholds.sameDeviceDistinctCustomersPerDay()) {
            out.add(new ProxyIndicator(ProxyIndicator.Kind.SAME_DEVICE, sameDeviceCustomers.getAsInt(),
                    thresholds.sameDeviceDistinctCustomersPerDay()));
        }
        if (thresholds.sameIpDistinctCustomersPerDay().isPresent() && sameIpCustomers.isPresent()
                && sameIpCustomers.getAsInt() >= thresholds.sameIpDistinctCustomersPerDay().getAsInt()) {
            out.add(new ProxyIndicator(ProxyIndicator.Kind.SAME_IP, sameIpCustomers.getAsInt(),
                    thresholds.sameIpDistinctCustomersPerDay().getAsInt()));
        }
        if (sentAt != null) {
            long seconds = Duration.between(sentAt, signedAt).toSeconds();
            if (seconds < thresholds.minSecondsFromSendToSign()) {
                out.add(new ProxyIndicator(ProxyIndicator.Kind.FAST_SIGN, seconds, thresholds.minSecondsFromSendToSign()));
            }
        }
        return List.copyOf(out);
    }
}
