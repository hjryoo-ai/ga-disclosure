package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.seal.evidence.EvidencePackageReader;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.sign.SignRejection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G6 완료 자동 전이(4 계획 §7.1·§7.3): 서명자 집합(CUSTOMER → AGENT → MANAGER, SEQUENTIAL)이 다 차는 마지막 서명의 트랜잭션에서 COMPLETED가 되고,
 * 서명본 PDF(원본이 바이트 접두)·증거 패키지(매니페스트·엔트리 해시 일치)가 문서 키로 암호화돼 기록되며, 보존기한은 완료일 앵커로 연장되고 모든 객체에 그
 * 기한의 잠금이 걸린다. 순서 위반은 업무 거부, 완료 중간 실패는 그 서명까지 롤백한다.
 */
class CompletionIT {

    private final SignSetup x = new SignSetup();

    @AfterEach
    void close() {
        x.close();
    }

    @Test
    void theLastRequiredSignatureCompletesInItsOwnTransaction() {
        DisclosureId id = x.sealed();
        SignService.Outcome customer = x.customerSignsOnTouchPad(id);
        assertThat(customer.accepted()).as("%s", customer.rejections()).isTrue();
        assertThat(customer.completed()).isFalse();
        assertThat(x.status(id)).isEqualTo("PARTIALLY_SIGNED");

        x.clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(x.agentSigns(id).accepted()).isTrue();
        x.clock.advance(java.time.Duration.ofMinutes(5));
        SignService.Outcome manager = x.managerConfirms(id);
        assertThat(manager.accepted()).as("%s", manager.rejections()).isTrue();
        assertThat(manager.completed()).isTrue();
        assertThat(manager.retentionPending()).isFalse();
        assertThat(x.status(id)).isEqualTo("COMPLETED");
        assertThat(x.s.text("SELECT completed_at::text FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", x.w.tenant.value(), id.value()))
                .isNotNull();

        // 보존기한: 봉인일 2026-09-23 + 5년 → 완료일(KST) 2026-09-23 + 5년(같은 날이면 그대로, 연장만)
        assertThat(x.s.text("SELECT retention_until::text FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", x.w.tenant.value(), id.value()))
                .isEqualTo("2031-09-23");

        List<ArtifactRecord> artifacts = x.s.artifactsOf(id);
        assertThat(artifacts).extracting(ArtifactRecord::kind).contains(ArtifactKind.SIGNED_PDF, ArtifactKind.EVIDENCE_ZIP);
        Actor viewer = SealSetup.COMPLIANCE;
        byte[] original = ((ArtifactService.View.Granted) x.s.artifacts.view(Callers.of(x.w.tenant, viewer), id, ArtifactKind.PDF)).plaintext();
        byte[] signed = ((ArtifactService.View.Granted) x.s.artifacts.view(Callers.of(x.w.tenant, viewer), id, ArtifactKind.SIGNED_PDF)).plaintext();
        assertThat(signed.length).isGreaterThan(original.length);
        assertThat(Arrays.equals(signed, 0, original.length, original, 0, original.length)).as("sealed PDF is a byte prefix").isTrue();
        byte[] zip = ((ArtifactService.View.Granted) x.s.artifacts.view(Callers.of(x.w.tenant, viewer), id, ArtifactKind.EVIDENCE_ZIP)).plaintext();
        assertThat(EvidencePackageReader.verify(zip)).isEmpty();
        assertThat(manifestOf(zip).get("anchor").isNull()).as("완료 전 앵커가 없으면 null").isTrue();

        // 커밋 뒤 잠금: 산출물 4종 + 서명 증거 4종(고객·설계사 스트로크·이미지) 전부 완료 보존기한까지
        java.time.Instant until = SealService.retainUntilInstant(LocalDate.parse("2031-09-23"));
        artifacts.forEach(a -> assertThat(x.s.bucket.retention(a.storageKey())).as(a.kind().name()).hasValue(until));
        x.w.in(() -> x.s.records.evidence(id)).forEach(e -> assertThat(x.s.bucket.retention(e.storageKey())).hasValue(until));

        assertThat(x.s.actionsFor(id)).containsSubsequence(AuditAction.SIGNATURE_CAPTURED, AuditAction.SIGNATURE_CAPTURED,
                AuditAction.SIGNATURE_CAPTURED, AuditAction.DISCLOSURE_COMPLETED);
    }

    /** 5 계획 §8.1: 매니페스트 {@code anchor} = 완료 시점에 이미 있던 그 테넌트의 최신 앵커(패키지는 다시 만들지 않는다). */
    @Test
    void theManifestReferencesTheLatestAnchorThatExistedAtCompletion() {
        DisclosureId id = x.sealed();
        com.ga.disclosure.infra.persistence.AnchorRepository anchors = new com.ga.disclosure.infra.persistence.AnchorRepository(x.w.gateway);
        com.ga.disclosure.audit.tsa.stub.LocalStubTsa tsa = com.ga.disclosure.audit.tsa.stub.LocalStubTsa.ephemeral(x.clock);
        new com.ga.disclosure.workflow.anchor.AnchorJob(anchors, x.w.audit, x.w.tx, new com.ga.disclosure.rules.resolve.RuleResolver(x.w.rules),
                new com.ga.disclosure.audit.tsa.TimestampClient(tsa, com.ga.disclosure.audit.tsa.NonceSource.secure(), tsa.trustAnchors()), x.clock, Callers.authz(x.clock))
                .run(List.of(x.w.tenant), LocalDate.parse("2026-09-23"), AnchorJobIT.SYSTEM);
        com.ga.disclosure.audit.anchor.AnchorRecord anchor = x.w.tx.inTenant(x.w.tenant, anchors::latest).orElseThrow().record();

        assertThat(x.customerSignsOnTouchPad(id).accepted()).isTrue();
        x.clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(x.agentSigns(id).accepted()).isTrue();
        x.clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(x.managerConfirms(id).completed()).isTrue();

        byte[] zip = ((ArtifactService.View.Granted) x.s.artifacts.view(Callers.of(x.w.tenant, SealSetup.COMPLIANCE), id, ArtifactKind.EVIDENCE_ZIP)).plaintext();
        assertThat(EvidencePackageReader.verify(zip)).isEmpty();
        tools.jackson.databind.JsonNode ref = manifestOf(zip).get("anchor");
        assertThat(ref.get("anchorSeq").asLong()).isEqualTo(anchor.anchorSeq());
        assertThat(ref.get("anchorDate").asString()).isEqualTo("2026-09-23");
        assertThat(ref.get("leafHash").asString()).isEqualTo(anchor.leafHash());
        assertThat(ref.get("sealChainSeq").asLong()).isEqualTo(anchor.sealChainSeq()).isEqualTo(1);
        assertThat(ref.get("auditSeq").asLong()).isEqualTo(anchor.auditSeq());
    }

    private static tools.jackson.databind.JsonNode manifestOf(byte[] zip) {
        return com.ga.platform.canonical.Canonicalizer.parseStrict(new String(EvidencePackageReader.entries(zip).get("manifest.json"),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void sequentialOrderIsABusinessRejection() {
        DisclosureId id = x.sealed();
        SignService.Outcome agentFirst = x.agentSigns(id);
        assertThat(agentFirst.rejections()).containsExactly(SignRejection.ORDER_VIOLATION);
        assertThat(x.status(id)).isEqualTo("SEALED");
        assertThat(x.signaturesOf(id)).isEmpty();
        assertThat(x.s.actionsFor(id)).contains(AuditAction.DISCLOSURE_REJECT);
    }

    @Test
    void aFailureWhileCompletingRollsTheLastSignatureBack() {
        DisclosureId id = x.sealed();
        x.customerSignsOnTouchPad(id);
        x.agentSigns(id);
        int objectsBefore = x.s.objects();
        x.s.recordPort.failOnArtifact.set(true);               // 완료 산출물 기록 중 실패 주입
        assertThatThrownBy(() -> x.managerConfirms(id)).isInstanceOf(FailingPorts.InjectedFailure.class);
        assertThat(x.status(id)).isEqualTo("PARTIALLY_SIGNED");
        assertThat(x.signaturesOf(id)).extracting(s -> s.role()).containsExactly(SignerRole.CUSTOMER, SignerRole.AGENT);
        assertThat(x.s.objects()).as("uploaded but uncommitted objects stay for gc").isGreaterThan(objectsBefore);
        x.s.recordPort.failOnArtifact.set(false);
        assertThat(x.managerConfirms(id).completed()).isTrue();
    }

    @Test
    void tokensOfAnotherChannelCannotUploadAScan() {
        DisclosureId id = x.sealed();
        String token = x.issue(id, SignatureChannel.TOUCH_PAD);
        x.readyTouchPad(token);
        assertThatThrownBy(() -> x.signService.uploadPaperScan(Callers.of(x.w.tenant, SignSetup.AGENT), token,
                new com.ga.disclosure.workflow.sign.PaperScan(SignSetup.png(), "X", "000000000000"))).isInstanceOf(IllegalArgumentException.class);
    }
}
