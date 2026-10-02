package com.ga.disclosure.sign.proxy;

import com.ga.disclosure.domain.enums.SignatureChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.OptionalInt;

import static com.ga.disclosure.sign.proxy.ProxyIndicator.Kind.FAST_SIGN;
import static com.ga.disclosure.sign.proxy.ProxyIndicator.Kind.SAME_DEVICE;
import static com.ga.disclosure.sign.proxy.ProxyIndicator.Kind.SAME_IP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** G11(단위): 대리 서명 탐지 — REMOTE_LINK만, KST 달력일, 발송→서명 하한, 임계치는 데이터, IP 지표는 선택. */
class ProxySignatureDetectorTest {

    private static final ProxyThresholds BUNDLE = new ProxyThresholds(2, OptionalInt.empty(), 60);
    private static final Instant SENT = Instant.parse("2026-09-24T01:00:00Z");
    private static final Instant SIGNED = SENT.plusSeconds(600);

    @Test
    void twoCustomersOnOneDeviceTheSameDayRaiseAFlag() {
        assertThat(ProxySignatureDetector.evaluate(SignatureChannel.REMOTE_LINK, OptionalInt.of(2), OptionalInt.empty(), SENT, SIGNED, BUNDLE))
                .containsExactly(new ProxyIndicator(SAME_DEVICE, 2, 2));
        assertThat(ProxySignatureDetector.evaluate(SignatureChannel.REMOTE_LINK, OptionalInt.of(1), OptionalInt.empty(), SENT, SIGNED, BUNDLE))
                .isEmpty();
        assertThat(ProxySignatureDetector.evaluate(SignatureChannel.REMOTE_LINK, OptionalInt.empty(), OptionalInt.empty(), SENT, SIGNED, BUNDLE))
                .as("no fingerprint, no device indicator").isEmpty();
    }

    @Test
    void theDayIsTheSeoulCalendarDay() {
        // 23:59:59 KST와 다음 날 00:00 KST는 다른 날 — 이동 24시간이 아니다
        assertThat(ProxySignatureDetector.dayOf(Instant.parse("2026-09-24T14:59:59Z"))).isEqualTo(LocalDate.parse("2026-09-24"));
        assertThat(ProxySignatureDetector.dayOf(Instant.parse("2026-09-24T15:00:00Z"))).isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(ProxySignatureDetector.dayOf(Instant.parse("2026-09-23T15:00:00Z"))).isEqualTo(LocalDate.parse("2026-09-24"));
    }

    @ParameterizedTest
    @EnumSource(value = SignatureChannel.class, names = "REMOTE_LINK", mode = EnumSource.Mode.EXCLUDE)
    void onlyRemoteLinkCustomerSignaturesAreEvaluated(SignatureChannel channel) {
        assertThat(ProxySignatureDetector.evaluate(channel, OptionalInt.of(9), OptionalInt.of(9), SENT, SENT.plusSeconds(1), BUNDLE)).isEmpty();
    }

    @Test
    void signingFasterThanTheFloorAfterSendingIsFlagged() {
        assertThat(ProxySignatureDetector.evaluate(SignatureChannel.REMOTE_LINK, OptionalInt.of(1), OptionalInt.empty(), SENT,
                SENT.plusSeconds(59), BUNDLE)).containsExactly(new ProxyIndicator(FAST_SIGN, 59, 60));
        assertThat(ProxySignatureDetector.evaluate(SignatureChannel.REMOTE_LINK, OptionalInt.of(1), OptionalInt.empty(), SENT,
                SENT.plusSeconds(60), BUNDLE)).isEmpty();
        assertThat(ProxySignatureDetector.evaluate(SignatureChannel.REMOTE_LINK, OptionalInt.of(1), OptionalInt.empty(), null,
                SENT.plusSeconds(1), BUNDLE)).as("not sent, no timing indicator").isEmpty();
    }

    @Test
    void ipIndicatorIsOffUntilTheRuleSetsIt() {
        assertThat(ProxySignatureDetector.evaluate(SignatureChannel.REMOTE_LINK, OptionalInt.of(1), OptionalInt.of(50), SENT, SIGNED, BUNDLE))
                .isEmpty();
        ProxyThresholds withIp = new ProxyThresholds(2, OptionalInt.of(3), 60);
        assertThat(ProxySignatureDetector.evaluate(SignatureChannel.REMOTE_LINK, OptionalInt.of(1), OptionalInt.of(3), SENT, SIGNED, withIp))
                .containsExactly(new ProxyIndicator(SAME_IP, 3, 3));
    }

    @Test
    void thresholdsAreData() {
        ProxyThresholds strict = new ProxyThresholds(3, OptionalInt.empty(), 600);
        assertThat(ProxySignatureDetector.evaluate(SignatureChannel.REMOTE_LINK, OptionalInt.of(2), OptionalInt.empty(), SENT, SIGNED, strict))
                .isEmpty();
        assertThat(ProxySignatureDetector.evaluate(SignatureChannel.REMOTE_LINK, OptionalInt.of(3), OptionalInt.empty(), SENT,
                SENT.plusSeconds(599), strict)).extracting(ProxyIndicator::kind).containsExactly(SAME_DEVICE, FAST_SIGN);
        assertThatThrownBy(() -> new ProxyThresholds(1, OptionalInt.empty(), 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProxyThresholds(2, OptionalInt.of(1), 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProxyThresholds(2, OptionalInt.empty(), -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
