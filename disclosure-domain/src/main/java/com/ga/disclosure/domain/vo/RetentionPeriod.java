package com.ga.disclosure.domain.vo;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 보존기간(5 계획 §8.7, 승인 Q5): 앵커 날짜 + {@code years}년 + {@code days}일 — 산식은 이것 하나다(봉인 때와 완료·계약일 앵커 때 같은 식).
 * 합계는 1일 이상이다(룰 스키마도 강제한다) — 그래서 봉인 직후에 "이미 끝난 보존"은 생길 수 없다. 2월 29일 + n년은 2월 28일
 * ({@link LocalDate#plusYears}) 뒤에 일수를 더한다.
 */
public record RetentionPeriod(int years, int days) {

    public RetentionPeriod {
        if (years < 0 || days < 0 || (years == 0 && days == 0)) {
            throw new IllegalArgumentException("retention period must be at least one day: " + years + "y " + days + "d");
        }
    }

    public LocalDate from(LocalDate anchor) {
        return Objects.requireNonNull(anchor, "anchor").plusYears(years).plusDays(days);
    }
}
