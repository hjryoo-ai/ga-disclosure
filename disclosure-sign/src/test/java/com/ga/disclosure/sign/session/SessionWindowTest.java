package com.ga.disclosure.sign.session;

import com.ga.disclosure.sign.retention.SignDeadline;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionWindowTest {

    private static final Instant SEALED = Instant.parse("2026-09-23T01:00:00Z");            // 10:00 KST

    @Test
    void deadlineIsTheLastMicrosecondOfSealDayPlusDaysInSeoul() {
        SignDeadline d = SignDeadline.of(SEALED, 7);
        assertThat(d.lastDay()).isEqualTo(LocalDate.parse("2026-09-30"));
        assertThat(d.lastInstant()).isEqualTo(Instant.parse("2026-09-30T14:59:59.999999Z"));
        assertThat(d.passed(Instant.parse("2026-09-30T14:59:59.999999Z"))).isFalse();
        assertThat(d.passed(Instant.parse("2026-09-30T15:00:00Z"))).isTrue();
        // 봉인일은 KST 날짜: 23:30 KST 봉인은 그날이 D-0
        assertThat(SignDeadline.of(Instant.parse("2026-09-23T14:30:00Z"), 0).lastDay()).isEqualTo(LocalDate.parse("2026-09-23"));
        assertThat(SignDeadline.of(Instant.parse("2026-09-23T15:00:00Z"), 0).lastDay()).isEqualTo(LocalDate.parse("2026-09-24"));
    }

    @Test
    void sessionExpiresAtTheEarlierOfTtlAndDeadline() {
        Instant deadline = SignDeadline.of(SEALED, 7).lastInstant();
        Instant issued = Instant.parse("2026-09-24T01:00:00Z");
        assertThat(SessionWindow.expiresAt(issued, Duration.ofMinutes(30), deadline)).isEqualTo(issued.plusSeconds(1800));
        Instant late = Instant.parse("2026-09-30T14:00:00Z");
        assertThat(SessionWindow.expiresAt(late, Duration.ofHours(72), deadline)).isEqualTo(deadline);
        assertThatThrownBy(() -> SessionWindow.expiresAt(deadline.plusSeconds(1), Duration.ofHours(1), deadline))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionWindow.expiresAt(issued, Duration.ZERO, deadline)).isInstanceOf(IllegalArgumentException.class);
        Instant expires = issued.plusSeconds(1800);
        assertThat(SessionWindow.elapsed(expires, expires)).isFalse();
        assertThat(SessionWindow.elapsed(expires.plusNanos(1000), expires)).isTrue();
    }
}
