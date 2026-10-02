package com.ga.disclosure.sign.retention;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 서명 기한(설계서 §6.5, 4 계획 §7.4): D = 봉인일(Asia/Seoul) + {@code signDeadlineDays}, 기한 끝 = D 23:59:59.999999 KST(DB 마이크로초).
 * 기한이 지나면(끝 이후) 만료 배치가 EXPIRED로 옮긴다.
 */
public record SignDeadline(LocalDate lastDay) {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static SignDeadline of(Instant sealedAt, int signDeadlineDays) {
        if (signDeadlineDays < 0) {
            throw new IllegalArgumentException("signDeadlineDays < 0");
        }
        return new SignDeadline(sealedAt.atZone(SEOUL).toLocalDate().plusDays(signDeadlineDays));
    }

    /** 기한 끝(포함) = D 다음 날 0시 KST − 1µs. */
    public Instant lastInstant() {
        return lastDay.plusDays(1).atStartOfDay(SEOUL).toInstant().minus(1, ChronoUnit.MICROS);
    }

    public boolean passed(Instant asOf) {
        return asOf.isAfter(lastInstant());
    }
}
