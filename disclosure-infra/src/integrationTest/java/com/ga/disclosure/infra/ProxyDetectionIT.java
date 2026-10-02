package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.sign.IdentityInputs;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G11 대리 서명 탐지(설계서 §6.5, 4 계획 §5) — 통합 1건 묶음: 같은 기기 지문으로 같은 KST 달력일에 서로 다른 고객 2명이 REMOTE_LINK로 서명하면 두 번째
 * 서명에 {@code SIGNATURE_DEVICE_REUSE} 플래그(서명은 막지 않는다), KST 날짜가 바뀌면 없음, TOUCH_PAD는 대상 밖, 발송 후 최소 초 미만이면 플래그.
 * 감사에는 지표·값·임계치만 — 지문·IP 원값은 남지 않는다. 임계치는 룰 데이터(단위 산식은 ProxySignatureDetectorTest).
 */
class ProxyDetectionIT {

    private static SignService.Outcome remoteSign(SignSetup x, DisclosureId id, String fingerprint, Duration afterSend) {
        String token = x.issue(id, SignatureChannel.REMOTE_LINK);
        x.sessionService.verify(token, IdentityInputs.birthDate(SignSetup.BIRTH));
        x.clock.advance(afterSend);
        return x.signService.capture(token, SignSetup.capture(fingerprint, "198.51.100.7"));
    }

    private static List<DisclosureFlagPort.OpenFlag> reuseFlags(SignSetup x, DisclosureId id) {
        return x.openFlags(id).stream().filter(f -> f.type() == DisclosureFlagPort.Type.SIGNATURE_DEVICE_REUSE).toList();
    }

    private static List<JsonNode> raiseDetails(SignSetup x) {
        return x.s.audit().stream().map(AuditRecord::entry).filter(e -> e.action() == AuditAction.FLAG_RAISE)
                .filter(e -> e.detail().path("type").asString().equals("SIGNATURE_DEVICE_REUSE")).map(e -> e.detail()).toList();
    }

    @Test
    void theSameDeviceForTwoCustomersOnOneKstDayIsFlaggedButNotBlocked() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId first = x.sealed();
            DisclosureId second = x.sealedFor(x.newSigner("가상서명고객2"));
            assertThat(remoteSign(x, first, "fp-shared-1", Duration.ofMinutes(2)).accepted()).isTrue();
            assertThat(reuseFlags(x, first)).isEmpty();
            SignService.Outcome o = remoteSign(x, second, "fp-shared-1", Duration.ofMinutes(2));
            assertThat(o.accepted()).as("detection never blocks").isTrue();
            List<DisclosureFlagPort.OpenFlag> flags = reuseFlags(x, second);
            assertThat(flags).hasSize(1);
            assertThat(flags.getFirst().targetKind()).isEqualTo("SIGNATURE");
            assertThat(flags.getFirst().targetId()).isEqualTo(o.signatureId().orElseThrow().toString());
            JsonNode detail = raiseDetails(x).getFirst();
            assertThat(detail.at("/indicators/0/kind").asString()).isEqualTo("SAME_DEVICE");
            assertThat(detail.at("/indicators/0/value").asLong()).isEqualTo(2);
            assertThat(detail.at("/indicators/0/threshold").asLong()).isEqualTo(2);
            assertThat(x.s.audit().toString()).doesNotContain("fp-shared-1").doesNotContain("198.51.100.7");
        }
    }

    @Test
    void theKstDayBoundarySeparatesTheCount() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId first = x.sealed();
            DisclosureId second = x.sealedFor(x.newSigner("가상서명고객2"));
            x.clock.set(Instant.parse("2026-09-23T14:50:00Z"));        // 23:50 KST
            remoteSign(x, first, "fp-boundary", Duration.ofMinutes(2));
            x.clock.set(Instant.parse("2026-09-23T15:05:00Z"));        // 다음 날 00:05 KST
            remoteSign(x, second, "fp-boundary", Duration.ofMinutes(2));
            assertThat(reuseFlags(x, second)).isEmpty();
        }
    }

    @Test
    void oneKstDayAcrossTheUtcMidnightIsOneDay() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId first = x.sealed();
            DisclosureId second = x.sealedFor(x.newSigner("가상서명고객2"));
            x.clock.set(Instant.parse("2026-09-23T15:05:00Z"));        // 2026-09-24 00:05 KST(UTC로는 9-23)
            remoteSign(x, first, "fp-kst", Duration.ofMinutes(2));
            x.clock.set(Instant.parse("2026-09-24T14:40:00Z"));        // 2026-09-24 23:40 KST(UTC로는 9-24)
            remoteSign(x, second, "fp-kst", Duration.ofMinutes(2));
            assertThat(reuseFlags(x, second)).hasSize(1);
        }
    }

    @Test
    void touchPadSignaturesAreOutOfScope() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId first = x.sealed();
            DisclosureId second = x.sealedFor(x.newSigner("가상서명고객2"));
            x.customerSignsOnTouchPad(first);
            x.customerSignsOnTouchPad(second);                          // 같은 태블릿 지문 "tablet-1"
            assertThat(reuseFlags(x, first)).isEmpty();
            assertThat(reuseFlags(x, second)).isEmpty();
        }
    }

    @Test
    void aSignatureRightAfterTheLinkIsSentIsFlagged() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId id = x.sealed();
            remoteSign(x, id, "fp-fast", Duration.ofSeconds(30));         // 룰 minSecondsFromSendToSign = 60
            assertThat(reuseFlags(x, id)).hasSize(1);
            assertThat(raiseDetails(x).getFirst().at("/indicators/0/kind").asString()).isEqualTo("FAST_SIGN");
        }
    }

    @Test
    void thresholdsAreRuleData() {
        try (SignSetup x = new SignSetup(new SealSetup(WorkflowSetup.withRule(body -> ((tools.jackson.databind.node.ObjectNode) body
                .get("proxySignatureDetection")).put("sameDeviceDistinctCustomersPerDay", 3).put("minSecondsFromSendToSign", 10))))) {
            DisclosureId first = x.sealed();
            DisclosureId second = x.sealedFor(x.newSigner("가상서명고객2"));
            remoteSign(x, first, "fp-data", Duration.ofSeconds(30));
            remoteSign(x, second, "fp-data", Duration.ofSeconds(30));
            assertThat(reuseFlags(x, first)).isEmpty();
            assertThat(reuseFlags(x, second)).isEmpty();
        }
    }
}
