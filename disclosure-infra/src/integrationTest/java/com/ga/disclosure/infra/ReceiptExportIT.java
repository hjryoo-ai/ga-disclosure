package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.tsa.NonceSource;
import com.ga.disclosure.audit.tsa.TimestampClient;
import com.ga.disclosure.audit.tsa.stub.LocalStubTsa;
import com.ga.disclosure.audit.verify.PackageVerifier;
import com.ga.disclosure.audit.verify.Statements;
import com.ga.disclosure.audit.verify.VerifyReport;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.persistence.AnchorRepository;
import com.ga.disclosure.infra.persistence.SealChainRepository;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.anchor.AnchorJob;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.verify.ReceiptExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5(실제 산출물): 완료된 확인서의 증거 패키지 + 영수증 내보내기를 DB 없이 {@code verify package}가 MATCH로 받는다 — 상한(TSA 시각)과, 매니페스트가
 * 가리키는 직전 앵커가 문서보다 앞서면 하한(자체 기록) 문장. 아직 고정된 앵커가 덮지 않으면 내보내지 않는다. 내보내기는 감사된다.
 */
class ReceiptExportIT {

    final SignSetup x = new SignSetup();
    final AnchorRepository anchors = new AnchorRepository(x.w.gateway);
    final LocalStubTsa tsa = LocalStubTsa.ephemeral(x.clock);
    final ReceiptExporter exporter = new ReceiptExporter(new SealChainRepository(x.w.gateway), anchors, x.s.artifacts, x.w.audit, x.w.tx, x.clock, Callers.authz(x.clock));

    @AfterEach
    void close() {
        x.close();
    }

    /** 그 날(KST)의 시계로 앵커를 만든다 — 앵커 날짜 = 생성 시각의 KST 날짜(5 수용심사 R1, V12 CHECK). 지난 날은 시계를 그만큼 되돌린다. */
    AnchorJob.Report anchor(String day) {
        LocalDate today = LocalDate.ofInstant(x.clock.instant(), java.time.ZoneId.of("Asia/Seoul"));
        java.time.Clock onThatDay = java.time.Clock.offset(x.clock, Duration.ofDays(java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(day))));
        return new AnchorJob(anchors, x.w.audit, x.w.tx, new RuleResolver(x.w.rules), new TimestampClient(tsa, NonceSource.secure(), tsa.trustAnchors()),
                onThatDay, Callers.authz(onThatDay)).run(List.of(x.w.tenant), AnchorJobIT.SYSTEM);
    }

    DisclosureId completed() {
        DisclosureId id = x.sealed();
        assertThat(x.customerSignsOnTouchPad(id).accepted()).isTrue();
        x.clock.advance(Duration.ofMinutes(5));
        assertThat(x.agentSigns(id).accepted()).isTrue();
        x.clock.advance(Duration.ofMinutes(5));
        assertThat(x.managerConfirms(id).completed()).isTrue();
        return id;
    }

    byte[] evidenceZip(DisclosureId id) {
        return ((ArtifactService.View.Granted) x.s.artifacts.view(Callers.of(x.w.tenant, SealSetup.COMPLIANCE), id, ArtifactKind.EVIDENCE_ZIP)).plaintext();
    }

    @Test
    void aCompletedPackageAndItsReceiptProveExistenceBeforeTheTsaTime() {
        assertThat(anchor("2026-09-22").created()).hasSize(1);                     // 봉인 전 앵커 = 매니페스트가 가리킬 직전 앵커
        DisclosureId id = completed();
        assertThat(exporter.export(Callers.of(x.w.tenant, SealSetup.COMPLIANCE), id)).isEqualTo(new ReceiptExporter.Result.NotAvailable("NOT_YET_COVERED"));

        AnchorJob.Report covering = anchor("2026-09-23");
        assertThat(covering.receipts()).isEqualTo(1);
        ReceiptExporter.Result result = exporter.export(Callers.of(x.w.tenant, SealSetup.COMPLIANCE), id);
        assertThat(result).isInstanceOf(ReceiptExporter.Result.Exported.class);
        byte[] receipt = ((ReceiptExporter.Result.Exported) result).bytes();
        assertThat(new String(receipt, StandardCharsets.UTF_8)).doesNotContain("가상서명고객", SignSetup.PHONE);

        byte[] trust = tsa.trustAnchors().toPem().getBytes(StandardCharsets.US_ASCII);
        VerifyReport report = PackageVerifier.verify(evidenceZip(id), receipt, trust, Instant.parse("2026-10-03T00:00:00Z"));

        assertThat(report.findings()).isEmpty();
        Instant t1 = covering.batches().getFirst().genTime();
        assertThat(report.conclusion().existedBefore()).isEqualTo(t1);
        assertThat(report.statements()).contains(Statements.existedBefore(t1));
        assertThat(report.conclusion().sealedAfter()).isNotNull();
        assertThat(report.conclusion().sealedAfter().sealChainSeq()).isZero();
        assertThat(report.statements()).anyMatch(s -> s.startsWith("이 문서는 2026-09-22 앵커의 봉인 체인 머리(seq 0, 기록 시각 "));
        assertThat(x.s.actionsFor(id)).contains(AuditAction.ANCHOR_RECEIPT_EXPORTED);

        // 패키지만: 고정 문장
        VerifyReport alone = PackageVerifier.verify(evidenceZip(id), null, null, Instant.parse("2026-10-03T00:00:00Z"));
        assertThat(alone.findings()).isEmpty();
        assertThat(alone.statements()).containsExactly(Statements.INTERNAL_ONLY);
    }

    @Test
    void withoutAPreviousAnchorThereIsNoLowerBound() {
        DisclosureId id = completed();
        anchor("2026-09-23");
        byte[] receipt = ((ReceiptExporter.Result.Exported) exporter.export(Callers.of(x.w.tenant, SealSetup.COMPLIANCE), id)).bytes();

        VerifyReport report = PackageVerifier.verify(evidenceZip(id), receipt, tsa.trustAnchors().toPem().getBytes(StandardCharsets.US_ASCII),
                Instant.parse("2026-10-03T00:00:00Z"));

        assertThat(report.findings()).isEmpty();
        assertThat(report.conclusion().sealedAfter()).isNull();
        assertThat(report.statements()).noneMatch(s -> s.contains("뒤에 봉인되었다"));
    }
}
