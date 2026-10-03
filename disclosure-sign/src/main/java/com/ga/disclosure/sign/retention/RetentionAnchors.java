package com.ga.disclosure.sign.retention;

import com.ga.disclosure.domain.enums.RetentionAnchor;
import com.ga.disclosure.domain.vo.RetentionPeriod;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 보존기한 앵커 산식(3B 수용심사 §3-3, 5 계획 승인 Q5): {@code retention_until = max(현재, max(앵커 날짜 + 보존기간))} — 보존기간은
 * {@link RetentionPeriod}(년 + 일, 산식 하나). 앵커는 룰
 * {@code retentionAnchors} 중 날짜가 생긴 것만 쓴다(SEAL = 봉인일, COMPLETION = 완료일 KST, CONTRACT_DATE = 계약일 — Phase 6 연결).
 * 결과는 현재보다 짧아지지 않는다(연장만, DB GD094·Object Lock COMPLIANCE와 같은 의미).
 */
public final class RetentionAnchors {

    private RetentionAnchors() {
    }

    /**
     * @param current 지금 기한(봉인 전이면 {@code null})
     * @param anchors 룰 {@code retentionAnchors}
     * @param dates   생긴 앵커 날짜(룰에 없는 앵커의 날짜는 무시)
     */
    public static LocalDate until(LocalDate current, List<RetentionAnchor> anchors, Map<RetentionAnchor, LocalDate> dates, RetentionPeriod period) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(dates, "dates");
        LocalDate best = current;
        for (RetentionAnchor anchor : anchors) {
            LocalDate date = dates.get(anchor);
            if (date == null) {
                continue;
            }
            LocalDate candidate = period.from(date);
            if (best == null || candidate.isAfter(best)) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("no retention anchor date is available among " + anchors);
        }
        return best;
    }
}
