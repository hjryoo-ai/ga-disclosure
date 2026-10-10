package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.audit.tsa.NonceSource;
import com.ga.disclosure.audit.tsa.TimestampClient;
import com.ga.disclosure.audit.tsa.TimestampFailure;
import com.ga.disclosure.audit.tsa.stub.LocalStubTsa;
import com.ga.disclosure.audit.verify.FindingCode;
import com.ga.disclosure.audit.verify.VerifyReport;
import com.ga.disclosure.audit.verify.VerifySchemas;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.persistence.AnchorRepository;
import com.ga.disclosure.infra.persistence.SealChainRepository;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.anchor.AnchorJob;
import com.ga.disclosure.workflow.verify.TenantVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G6(5 계획 §8.4): 정상 테넌트는 0. 슈퍼유저가 트리거를 끄고 바꾼 5종(봉인 {@code chain_hash}, 감사 {@code entry_hash}, 객체 바이트, 앵커 잎, 영수증
 * 경로)은 각각 불일치(2) + 코드 + {@code CHAIN_BROKEN} 플래그(끊긴 지점의 확인서, 정할 수 없으면 테넌트). 영수증 없이 기한을 넘긴 앵커는
 * {@code ANCHOR_UNSTAMPED}(불일치지만 운영 신호라 플래그 없음). 쓰기는 {@code VERIFY_RUN} 1행과 플래그뿐이다.
 */
class VerifyTenantIT {

    final SignSetup x = new SignSetup();
    final AnchorRepository anchors = new AnchorRepository(x.w.gateway);
    final LocalStubTsa tsa = LocalStubTsa.ephemeral(x.clock);
    final byte[] trust = tsa.trustAnchors().toPem().getBytes(StandardCharsets.US_ASCII);

    @AfterEach
    void close() {
        x.close();
    }

    TenantVerifier verifier(Clock clock) {
        return new TenantVerifier(x.w.audit, new SealChainRepository(x.w.gateway), anchors, x.s.records, x.s.cipher, x.s.bucket,
                new RuleResolver(x.w.rules), x.w.flags, x.w.tx, clock, Callers.authz(clock), new com.ga.disclosure.infra.persistence.KekRewrapRepository(x.w.gateway),
                x.w.keys);
    }

    VerifyReport verify() {
        return verifier(x.clock).run(Callers.of(x.w.tenant, SealSetup.COMPLIANCE), trust);
    }

    AnchorJob.Report anchor(String day, com.ga.disclosure.audit.tsa.TimestampAuthorityPort port) {
        return new AnchorJob(anchors, x.w.audit, x.w.tx, new RuleResolver(x.w.rules), new TimestampClient(port, NonceSource.secure(), tsa.trustAnchors()),
                x.clock, Callers.authz(x.clock)).run(List.of(x.w.tenant), LocalDate.parse(day), AnchorJobIT.SYSTEM);
    }

    DisclosureId completedAndAnchored() {
        DisclosureId id = x.sealed();
        assertThat(x.customerSignsOnTouchPad(id).accepted()).isTrue();
        x.clock.advance(Duration.ofMinutes(5));
        assertThat(x.agentSigns(id).accepted()).isTrue();
        x.clock.advance(Duration.ofMinutes(5));
        assertThat(x.managerConfirms(id).completed()).isTrue();
        assertThat(anchor("2026-09-23", tsa).receipts()).isEqualTo(1);
        return id;
    }

    void asSuperuser(String sql, Object... params) {
        try (Connection c = x.w.db.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.execute("SET LOCAL session_replication_role = replica");          // 트리거(GD030·110·111 등)를 끈 변조
            }
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<FindingCode> codes(VerifyReport r) {
        return r.findings().stream().map(VerifyReport.Finding::code).distinct().toList();
    }

    long openChainBroken(String targetKind, String targetId) {
        return x.s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ? AND type = 'CHAIN_BROKEN' AND target_kind = ? AND target_id = ?"
                + " AND resolved_at IS NULL", x.w.tenant.value(), targetKind, targetId);
    }

    @Test
    void anUntouchedTenantMatchesAndOnlyTheRunIsRecorded() {
        completedAndAnchored();
        long flagsBefore = x.s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ?", x.w.tenant.value());

        VerifyReport r = verify();

        assertThat(r.findings()).isEmpty();
        assertThat(r.exitCode()).isZero();
        assertThat(VerifySchemas.report(r.toJson())).isEmpty();
        assertThat(r.counts().disclosures()).isEqualTo(1);
        assertThat(r.counts().objects()).isGreaterThanOrEqualTo(4);
        assertThat(r.counts().anchors()).isEqualTo(1);
        assertThat(r.counts().receipts()).isEqualTo(1);
        AuditRecord run = x.s.audit().getLast();
        assertThat(run.entry().action()).isEqualTo(AuditAction.VERIFY_RUN);
        assertThat(run.entry().detail().get("reportSha256").asString()).isEqualTo(r.sha256());
        assertThat(x.s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ?", x.w.tenant.value())).isEqualTo(flagsBefore);
    }

    @Test
    void aRewrittenChainHashBreaksTheSealChainAndFlagsThatDisclosure() {
        DisclosureId id = completedAndAnchored();
        asSuperuser("UPDATE disclosure SET chain_hash = ? WHERE tenant_id = ? AND disclosure_id = ?", "e".repeat(64), x.w.tenant.value(), id.value());

        VerifyReport r = verify();

        assertThat(r.exitCode()).isEqualTo(2);
        assertThat(codes(r)).contains(FindingCode.SEAL_CHAIN_BROKEN);
        assertThat(openChainBroken("DISCLOSURE", id.toString())).isEqualTo(1);
    }

    @Test
    void aRewrittenAuditEntryHashBreaksTheAuditChainAndFlagsThatRow() {
        completedAndAnchored();
        asSuperuser("UPDATE audit_log SET entry_hash = ? WHERE tenant_id = ? AND seq = 3", "d".repeat(64), x.w.tenant.value());

        VerifyReport r = verify();

        assertThat(codes(r)).contains(FindingCode.AUDIT_CHAIN_BROKEN);
        assertThat(r.findings()).anySatisfy(f -> assertThat(f.where()).containsEntry("seq", 3L));
        assertThat(openChainBroken("AUDIT_LOG", "3")).as("확인서를 정할 수 없으면 끊긴 감사 행(5 계획 §1.6)").isEqualTo(1);
        assertThat(openChainBroken("AUDIT_LOG", "4")).as("다음 행의 prevHash도 끊긴다").isEqualTo(1);
        assertThat(x.s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ? AND type = 'CHAIN_BROKEN' AND target_kind = 'AUDIT_LOG'"
                + " AND disclosure_id IS NULL", x.w.tenant.value())).isEqualTo(2);
    }

    @Test
    void replacedObjectBytesAreAHashMismatch() {
        DisclosureId id = completedAndAnchored();
        String key = x.s.artifactsOf(id).getFirst().storageKey();
        x.s.bucket.put(key, "not the sealed ciphertext".getBytes(StandardCharsets.US_ASCII));    // 잠긴 버전은 남고 최신 버전만 바뀐다

        VerifyReport r = verify();

        assertThat(codes(r)).containsExactly(FindingCode.OBJECT_HASH_MISMATCH);
        assertThat(r.findings().getFirst().where()).containsEntry("storageKey", key);
        assertThat(openChainBroken("DISCLOSURE", id.toString())).isEqualTo(1);
    }

    /** 복호화는 되지만 평문이 기록과 다르다(기록 해시를 바꾼 경우) — 기대·실제 해시가 보고서에 남는다. */
    @Test
    void aRewrittenArtifactHashIsAHashMismatchWithBothHashes() {
        DisclosureId id = completedAndAnchored();
        var artifact = x.s.artifactsOf(id).getFirst();
        asSuperuser("UPDATE document_artifact SET sha256 = ? WHERE tenant_id = ? AND disclosure_id = ? AND kind = ?", "a".repeat(64),
                x.w.tenant.value(), id.value(), artifact.kind().name());

        VerifyReport r = verify();

        assertThat(codes(r)).containsExactly(FindingCode.OBJECT_HASH_MISMATCH);
        assertThat(r.findings().getFirst().detail()).containsEntry("expected", "a".repeat(64))
                .containsEntry("actual", artifact.sha256().hex());
    }

    @Test
    void aRewrittenAnchorLeafIsAnAnchorMismatch() {
        completedAndAnchored();
        asSuperuser("UPDATE anchor SET leaf_hash = ? WHERE tenant_id = ? AND anchor_seq = 1", "c".repeat(64), x.w.tenant.value());

        VerifyReport r = verify();

        assertThat(codes(r)).contains(FindingCode.ANCHOR_MISMATCH);
        assertThat(r.findings()).anySatisfy(f -> assertThat(f.detail()).containsEntry("problem", "LEAF"));
        assertThat(openChainBroken("ANCHOR", "1")).isEqualTo(1);
    }

    @Test
    void aRewrittenReceiptPathIsAnInvalidPath() {
        completedAndAnchored();
        asSuperuser("UPDATE anchor_receipt SET merkle_path = jsonb_set(merkle_path, '{0}', to_jsonb(?::text)) WHERE tenant_id = ? AND anchor_seq = 1",
                "b".repeat(64), x.w.tenant.value());

        VerifyReport r = verify();

        assertThat(codes(r)).containsExactly(FindingCode.RECEIPT_PATH_INVALID);
        assertThat(openChainBroken("ANCHOR", "1")).isEqualTo(1);
    }

    /**
     * 6A R1: 앵커는 매일 만들어지고(빠진 날은 {@code ANCHOR_MISSING_DAY}) 날짜는 그 날의 시계다 — 그래서 사흘 내내 TSA가 실패한 테넌트를 만든다.
     * 기한을 넘긴 것은 첫 날(23일)뿐이다.
     */
    @Test
    void anAnchorLeftUnstampedPastTheAlertDaysIsReportedWithoutAFlag() {
        for (String day : List.of("2026-09-23", "2026-09-24", "2026-09-25")) {
            anchor(day, request -> {
                throw new TimestampFailure(TimestampFailure.Kind.UNAVAILABLE, "TRANSPORT");
            });
            x.clock.advance(Duration.ofDays(1));
        }
        Clock inTime = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC);      // KST 9-25: 23 + 2일 = 25 — 아직
        assertThat(verifier(inTime).run(Callers.of(x.w.tenant, SealSetup.COMPLIANCE), trust).findings()).isEmpty();

        Clock late = Clock.fixed(Instant.parse("2026-09-25T15:00:00Z"), ZoneOffset.UTC);        // KST 9-26
        VerifyReport r = verifier(late).run(Callers.of(x.w.tenant, SealSetup.COMPLIANCE), trust);

        assertThat(codes(r)).containsExactly(FindingCode.ANCHOR_UNSTAMPED);
        assertThat(r.findings()).singleElement().satisfies(f -> assertThat(f.where()).containsEntry("anchorDate", "2026-09-23"));
        assertThat(r.exitCode()).isEqualTo(2);
        assertThat(x.s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ? AND type = 'CHAIN_BROKEN'", x.w.tenant.value())).isZero();
    }

    /**
     * 5 수용심사 R1·6A 승인 Q10: 첫 앵커부터 어제(KST)까지 앵커가 없는 날은 구간마다 {@code ANCHOR_MISSING_DAY} 1건 — 가운데 공백과 끝의 공백, 오늘은
     * 아직 돌지 않았을 수 있어 넣지 않는다. 운영 신호라 불일치(종료 2)지만 {@code CHAIN_BROKEN}을 올리지 않는다.
     */
    @Test
    void missingAnchorDaysAreReportedPerGapUpToYesterdayWithoutAFlag() {
        anchor("2026-09-23", tsa);
        x.clock.advance(Duration.ofDays(3));
        anchor("2026-09-26", tsa);                                                                  // 24·25일 공백

        VerifyReport sameDay = verifier(x.clock).run(Callers.of(x.w.tenant, SealSetup.COMPLIANCE), trust);      // KST 9-26: 오늘은 셈하지 않는다
        assertThat(sameDay.findings()).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo(FindingCode.ANCHOR_MISSING_DAY);
            assertThat(f.where()).containsEntry("afterAnchorSeq", 1L).containsEntry("fromDate", "2026-09-24").containsEntry("toDate", "2026-09-25");
            assertThat(f.detail()).containsEntry("days", 2L);
        });
        assertThat(VerifySchemas.report(sameDay.toJson())).isEmpty();

        Clock later = Clock.fixed(Instant.parse("2026-09-29T01:00:00Z"), ZoneOffset.UTC);          // KST 9-29: 27·28일이 끝의 공백
        VerifyReport r = verifier(later).run(Callers.of(x.w.tenant, SealSetup.COMPLIANCE), trust);

        // 시험용 스텁 TSA 인증서는 1일 유효라 26일 앵커는 영수증이 없다 — 그 ANCHOR_UNSTAMPED(26 + 2 < 29)는 이 테스트의 대상이 아니다
        List<VerifyReport.Finding> missing = r.findings().stream().filter(f -> f.code() == FindingCode.ANCHOR_MISSING_DAY).toList();
        assertThat(missing).hasSize(2);
        assertThat(codes(r)).doesNotContain(FindingCode.SEAL_CHAIN_BROKEN, FindingCode.AUDIT_CHAIN_BROKEN, FindingCode.ANCHOR_MISMATCH);
        assertThat(missing.getLast().where()).containsEntry("afterAnchorSeq", 2L).containsEntry("fromDate", "2026-09-27")
                .containsEntry("toDate", "2026-09-28");
        assertThat(r.exitCode()).isEqualTo(2);
        assertThat(x.s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ? AND type = 'CHAIN_BROKEN'", x.w.tenant.value())).isZero();
    }
}
