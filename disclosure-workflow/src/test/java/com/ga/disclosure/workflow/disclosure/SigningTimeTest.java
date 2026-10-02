package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.rules.validation.ValidationSubject.SignatureMark;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 서명 시각은 확인서마다 엄격히 증가한다: max(시계를 µs로 자른 값, 직전 서명 + 1µs) — 같은 시각·뒤처진 인스턴스 시계에도 목록 순서 = 서명 순서. */
class SigningTimeTest {

    private static final Instant T = Instant.parse("2026-09-23T03:00:00.000001Z");

    @Test
    void theFirstSignatureTakesTheClockAtDatabasePrecision() {
        Disclosure d = DisclosureTransitionTest.in(DisclosureStatus.SEALED);
        assertThat(SignSupport.signingTime(d, Instant.parse("2026-09-23T03:00:00.000001999Z"))).isEqualTo(T);
    }

    @Test
    void aLaterSignatureNeverTiesOrPrecedesThePreviousOne() {
        Disclosure d = DisclosureTransitionTest.in(DisclosureStatus.SEALED);
        d.sign(new SignatureMark(SignerRole.CUSTOMER, T), null, Fixtures.CHECK);
        assertThat(SignSupport.signingTime(d, T)).isEqualTo(T.plusNanos(1000));
        assertThat(SignSupport.signingTime(d, T.minusSeconds(5))).as("a lagging clock").isEqualTo(T.plusNanos(1000));
        assertThat(SignSupport.signingTime(d, T.plusSeconds(5))).isEqualTo(T.plusSeconds(5));
    }
}
