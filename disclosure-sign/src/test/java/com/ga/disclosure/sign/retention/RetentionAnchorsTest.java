package com.ga.disclosure.sign.retention;

import com.ga.disclosure.domain.enums.RetentionAnchor;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.ga.disclosure.domain.enums.RetentionAnchor.COMPLETION;
import static com.ga.disclosure.domain.enums.RetentionAnchor.CONTRACT_DATE;
import static com.ga.disclosure.domain.enums.RetentionAnchor.SEAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 보존기한 = max(현재, max(앵커 + 연수)), 연장만, 앵커 목록은 데이터(3B 수용심사 §3-3). */
class RetentionAnchorsTest {

    private static final List<RetentionAnchor> ALL = List.of(SEAL, COMPLETION, CONTRACT_DATE);
    private static final LocalDate SEALED = LocalDate.parse("2026-09-23");
    private static final LocalDate COMPLETED = LocalDate.parse("2026-09-28");

    @Test
    void latestAnchorPlusYearsWins() {
        assertThat(RetentionAnchors.until(null, ALL, Map.of(SEAL, SEALED), 5)).isEqualTo(LocalDate.parse("2031-09-23"));
        assertThat(RetentionAnchors.until(LocalDate.parse("2031-09-23"), ALL, Map.of(SEAL, SEALED, COMPLETION, COMPLETED), 5))
                .isEqualTo(LocalDate.parse("2031-09-28"));
        assertThat(RetentionAnchors.until(LocalDate.parse("2031-09-28"), ALL,
                Map.of(SEAL, SEALED, COMPLETION, COMPLETED, CONTRACT_DATE, LocalDate.parse("2026-10-15")), 5))
                .isEqualTo(LocalDate.parse("2031-10-15"));
    }

    @Test
    void neverShorterThanTheCurrentDeadline() {
        LocalDate extended = LocalDate.parse("2036-01-01");                          // 예: 수기로 연장된 기한
        assertThat(RetentionAnchors.until(extended, ALL, Map.of(SEAL, SEALED, COMPLETION, COMPLETED), 5)).isEqualTo(extended);
    }

    @Test
    void anchorListIsData() {
        Map<RetentionAnchor, LocalDate> dates = Map.of(SEAL, SEALED, COMPLETION, COMPLETED);
        assertThat(RetentionAnchors.until(null, List.of(SEAL), dates, 5)).isEqualTo(LocalDate.parse("2031-09-23"));
        assertThat(RetentionAnchors.until(null, List.of(COMPLETION), dates, 5)).isEqualTo(LocalDate.parse("2031-09-28"));
        assertThat(RetentionAnchors.until(null, List.of(SEAL), dates, 10)).isEqualTo(LocalDate.parse("2036-09-23"));
    }

    @Test
    void edgeCases() {
        assertThat(RetentionAnchors.until(null, ALL, Map.of(SEAL, LocalDate.parse("2028-02-29")), 5)).isEqualTo(LocalDate.parse("2033-02-28"));
        assertThatThrownBy(() -> RetentionAnchors.until(null, List.of(COMPLETION), Map.of(SEAL, SEALED), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetentionAnchors.until(null, ALL, Map.of(SEAL, SEALED), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
