package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.enums.SignatureEvidenceKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.artifact.ArtifactUnreadableException;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.SignatureEvidenceRecord;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G5: 서명 증거 객체는 확인서 문서 키(DEK 재사용, 3B 수용심사 §3-4)로 암호화돼 저장된다 — 버킷 원시 바이트에 PNG 시그니처({@code 89 50 4E 47})·
 * 좌표 문자열이 없고, 열람(복호화 + 평문 해시 대조)은 일치하며, AAD 교차(다른 서명·종류·확인서)는 풀리지 않고, 문서 키를 파기하면 증거도 읽을 수
 * 없다. 재적용·잔여물 정리는 산출물과 같은 경로로 증거 객체를 다룬다(승인 Q2).
 */
class SignatureEvidenceEncryptionIT {

    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 1, 2, 3};
    private static final String STROKES = "[{\"t\":0,\"x\":123.5,\"y\":45.25},{\"t\":16,\"x\":124.75,\"y\":46.5}]";

    private final SealSetup s = new SealSetup();

    @AfterEach
    void close() {
        s.close();
    }

    private record Stored(DisclosureId id, UUID signatureId, SignatureEvidenceRecord image, SignatureEvidenceRecord strokes) {
    }

    /** 봉인 → 설계사 서명 1건 → 이미지·스트로크 증거를 문서 키로 암호화해 올리고 기록한다(완료 유스케이스가 할 일을 그대로). */
    private Stored signedWithEvidence() {
        DisclosureId id = s.sealReasoned().id();
        String doc = s.text("SELECT canonical_hash FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", s.w.tenant.value(), id.value());
        String pdf = s.text("SELECT pdf_hash FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", s.w.tenant.value(), id.value());
        UUID[] signature = new UUID[1];
        s.w.db.seed(s.w.tenant.value(), c -> signature[0] = SeedData.signature(c, s.w.tenant.value(), id.value(), "AGENT", doc, pdf));
        DocumentCryptoPort.StoredKey key = s.w.in(() -> s.records.liveKey(id).orElseThrow());
        SignatureEvidenceRecord image = put(id, signature[0], key, SignatureEvidenceKind.IMAGE, PNG);
        SignatureEvidenceRecord strokes = put(id, signature[0], key, SignatureEvidenceKind.STROKES, STROKES.getBytes(StandardCharsets.UTF_8));
        return new Stored(id, signature[0], image, strokes);
    }

    private SignatureEvidenceRecord put(DisclosureId id, UUID signature, DocumentCryptoPort.StoredKey key, SignatureEvidenceKind kind, byte[] plain) {
        byte[] cipher = s.cipher.encryptEvidence(s.w.tenant, id, key, signature, kind, plain);
        Sha256 cipherHash = Sha256.of(com.ga.platform.canonical.Sha256.of(cipher));
        String storageKey = SignatureEvidenceRecord.storageKey(s.w.tenant.value(), id, signature, kind, cipherHash);
        s.bucket.put(storageKey, cipher);
        SignatureEvidenceRecord r = new SignatureEvidenceRecord(id, signature, kind, storageKey, Sha256.of(com.ga.platform.canonical.Sha256.of(plain)),
                plain.length, cipherHash, cipher.length, key.keyId(), s.w.clock.instant(), null);
        s.w.in(() -> {
            s.records.insertEvidence(r);
            return null;
        });
        return r;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    void bucketHoldsOnlyCiphertextAndViewingDecryptsToTheRecordedHash() {
        Stored st = signedWithEvidence();
        for (SignatureEvidenceRecord r : new SignatureEvidenceRecord[]{st.image(), st.strokes()}) {
            byte[] raw = s.bucket.get(r.storageKey());
            assertThat(raw[0]).isEqualTo((byte) 0x01);
            assertThat(raw).hasSize((int) r.cipherBytes());
            assertThat(r.cipherBytes()).isEqualTo(r.bytes() + 29);
            assertThat(contains(raw, new byte[]{(byte) 0x89, 'P', 'N', 'G'})).as("PNG signature").isFalse();
            assertThat(contains(raw, "123.5".getBytes(StandardCharsets.US_ASCII))).as("coordinates").isFalse();
            assertThat(r.storageKey()).startsWith(s.w.tenant.value() + "/" + st.id().value() + "/SIG/" + st.signatureId() + "/")
                    .doesNotContain(r.sha256().hex());
        }
        ArtifactService.View image = s.artifacts.viewEvidence(s.w.tenant, SealSetup.COMPLIANCE, st.id(), st.signatureId(), SignatureEvidenceKind.IMAGE);
        assertThat(((ArtifactService.View.Granted) image).plaintext()).isEqualTo(PNG);
        ArtifactService.View strokes = s.artifacts.viewEvidence(s.w.tenant, SealSetup.COMPLIANCE, st.id(), st.signatureId(),
                SignatureEvidenceKind.STROKES);
        assertThat(new String(((ArtifactService.View.Granted) strokes).plaintext(), StandardCharsets.UTF_8)).isEqualTo(STROKES);
        assertThat(s.audit()).anyMatch(r -> r.entry().action() == AuditAction.ARTIFACT_VIEW
                && r.entry().detail().path("signatureId").asString().equals(st.signatureId().toString()));
        assertThat(s.artifacts.viewEvidence(s.w.tenant, SealSetup.COMPLIANCE, st.id(), st.signatureId(), SignatureEvidenceKind.SCAN))
                .isEqualTo(new ArtifactService.View.Denied(ArtifactService.View.Reason.NO_ARTIFACT));
    }

    /**
     * 유스케이스 경로(4 계획 §7.1 4항): 터치 서명이 올린 스트로크·이미지가 버킷에는 암호문으로만 있고(PNG 시그니처·좌표 문자열 없음), 열람하면 받은 PNG·
     * 정규화된 스트로크와 같으며, 완료 증거 패키지에는 스트로크 원본이 들어가지 않는다(해시만).
     */
    @Test
    void theCapturePathStoresOnlyCiphertextAndThePackageCarriesOnlyHashes() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId id = x.sealed();
            UUID signatureId = x.customerSignsOnTouchPad(id).signatureId().orElseThrow();
            x.agentSigns(id);
            x.managerConfirms(id);
            List<SignatureEvidenceRecord> evidence = x.w.in(() -> x.s.records.evidence(id));
            assertThat(evidence).hasSize(4);
            byte[] coordinate = "\"x\":131".getBytes(StandardCharsets.US_ASCII);
            for (SignatureEvidenceRecord r : evidence) {
                byte[] raw = x.s.bucket.get(r.storageKey());
                assertThat(contains(raw, new byte[]{(byte) 0x89, 'P', 'N', 'G'})).as("PNG signature in %s", r.kindName()).isFalse();
                assertThat(contains(raw, coordinate)).as("coordinates in %s", r.kindName()).isFalse();
            }
            ArtifactService.View image = x.s.artifacts.viewEvidence(x.w.tenant, SealSetup.COMPLIANCE, id, signatureId, SignatureEvidenceKind.IMAGE);
            assertThat(((ArtifactService.View.Granted) image).plaintext()).isEqualTo(SignSetup.png());
            ArtifactService.View strokes = x.s.artifacts.viewEvidence(x.w.tenant, SealSetup.COMPLIANCE, id, signatureId, SignatureEvidenceKind.STROKES);
            assertThat(contains(((ArtifactService.View.Granted) strokes).plaintext(), coordinate)).isTrue();
            byte[] zip = ((ArtifactService.View.Granted) x.s.artifacts.view(x.w.tenant, SealSetup.COMPLIANCE, id,
                    com.ga.disclosure.domain.enums.ArtifactKind.EVIDENCE_ZIP)).plaintext();
            assertThat(contains(zip, coordinate)).as("no strokes in the evidence package").isFalse();
            java.util.Map<String, byte[]> entries = com.ga.disclosure.seal.evidence.EvidencePackageReader.entries(zip);
            assertThat(entries.keySet()).noneMatch(n -> n.endsWith(".png") || n.contains("stroke"));
            String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
            evidence.forEach(r -> assertThat(manifest).contains(r.sha256().hex()));
        }
    }

    @Test
    void ciphertextMovedToAnotherSignatureKindOrDisclosureDoesNotOpen() {
        Stored st = signedWithEvidence();
        DocumentCryptoPort.StoredKey key = s.w.in(() -> s.records.liveKey(st.id()).orElseThrow());
        byte[] cipher = s.bucket.get(st.image().storageKey());
        assertThat(s.cipher.openEvidence(s.w.tenant, st.id(), key, st.signatureId(), SignatureEvidenceKind.IMAGE, cipher)).isEqualTo(PNG);
        assertThatThrownBy(() -> s.cipher.openEvidence(s.w.tenant, st.id(), key, UUID.randomUUID(), SignatureEvidenceKind.IMAGE, cipher))
                .isInstanceOf(ArtifactUnreadableException.class);
        assertThatThrownBy(() -> s.cipher.openEvidence(s.w.tenant, st.id(), key, st.signatureId(), SignatureEvidenceKind.STROKES, cipher))
                .isInstanceOf(ArtifactUnreadableException.class);
        assertThatThrownBy(() -> s.cipher.openEvidence(s.w.tenant, DisclosureId.of(UUID.randomUUID()), key, st.signatureId(),
                SignatureEvidenceKind.IMAGE, cipher)).isInstanceOf(ArtifactUnreadableException.class);
        // 산출물 AAD로도 풀리지 않는다(같은 DEK지만 맥락이 다르다)
        assertThatThrownBy(() -> s.cipher.open(s.w.tenant, st.id(), key, com.ga.disclosure.domain.enums.ArtifactKind.PDF, cipher))
                .isInstanceOf(ArtifactUnreadableException.class);
    }

    @Test
    void shreddingTheDocumentKeyMakesEvidenceUnreadableToo() {
        Stored st = signedWithEvidence();
        s.w.db.seed(s.w.tenant.value(), c -> com.ga.disclosure.infra.testing.SeedData.shredDocumentKey(c, s.w.tenant.value(), st.id().value()));
        assertThat(s.artifacts.viewEvidence(s.w.tenant, SealSetup.COMPLIANCE, st.id(), st.signatureId(), SignatureEvidenceKind.IMAGE))
                .isEqualTo(new ArtifactService.View.Denied(ArtifactService.View.Reason.KEY_SHREDDED));
        assertThat(s.bucket.exists(st.image().storageKey())).as("the locked copy stays, unreadable").isTrue();
    }

    @Test
    void reconcileLocksEvidenceLikeArtifactsAndRelocksWhenRetentionGrows() {
        Stored st = signedWithEvidence();
        String until = s.text("SELECT retention_until::text FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", s.w.tenant.value(),
                st.id().value());
        ArtifactService.ReconcileReport first = s.artifacts.reconcile(s.w.tenant, SealSetup.MANAGER, 100);
        assertThat(first.applied()).isEqualTo(2);                                      // 봉인이 산출물은 이미 잠갔다 — 증거 2건
        assertThat(s.bucket.retention(st.image().storageKey())).isPresent();
        assertThat(s.text("SELECT retention_applied_until::text FROM signature_evidence WHERE tenant_id = ? AND signature_id = ? AND kind = 'IMAGE'",
                s.w.tenant.value(), st.signatureId())).isEqualTo(until);
        assertThat(s.artifacts.reconcile(s.w.tenant, SealSetup.MANAGER, 100).applied()).isZero();
        assertThat(s.audit()).anyMatch(r -> r.entry().action() == AuditAction.ARTIFACT_RETAIN
                && r.entry().detail().path("signatureId").asString().equals(st.signatureId().toString()));

        // 보존기한이 늘면(완료·계약 연결) 산출물·증거 전부 다시 건다 — 기한은 증가만
        LocalDate longer = LocalDate.parse(until).plusYears(5);
        s.w.db.seed(s.w.tenant.value(), c -> SeedData.exec(c, "UPDATE disclosure SET retention_until = ? WHERE tenant_id = ? AND disclosure_id = ?",
                longer, s.w.tenant.value(), st.id().value()));
        assertThat(s.artifacts.reconcile(s.w.tenant, SealSetup.MANAGER, 100).applied()).isEqualTo(4);  // PDF·CANONICAL + 증거 2
        assertThat(s.bucket.retention(st.strokes().storageKey()).orElseThrow()).isAfter(Instant.parse(longer + "T00:00:00Z"));
        assertThat(s.text("SELECT retention_applied_until::text FROM document_artifact WHERE tenant_id = ? AND disclosure_id = ? AND kind = 'PDF'",
                s.w.tenant.value(), st.id().value())).isEqualTo(longer.toString());
    }

    @Test
    void gcKeepsReferencedEvidenceAndRemovesAnOrphanUnderTheSamePrefix() {
        Stored st = signedWithEvidence();
        String orphan = s.w.tenant.value() + "/" + st.id().value() + "/SIG/" + UUID.randomUUID() + "/IMAGE/" + "e".repeat(64);
        s.bucket.put(orphan, new byte[]{1, 2, 3});
        // 객체의 마지막 수정 시각은 저장소의 실제 시각이다 — 유예를 넘긴 미래 시계로 본다(RetentionOrderIT와 같은 방식)
        ArtifactService later = s.artifactsAt(Clock.offset(Clock.systemUTC(), SealSetup.grace().plus(Duration.ofHours(1))), s.bucket);
        ArtifactService.GcReport report = later.gc(s.w.tenant, SealSetup.MANAGER, SealSetup.grace());
        assertThat(report.deleted()).containsExactly(orphan);
        assertThat(report.referenced()).isEqualTo(4);                                   // PDF·CANONICAL + 증거 2
        assertThat(s.bucket.exists(st.image().storageKey())).isTrue();
        assertThat(s.bucket.exists(st.strokes().storageKey())).isTrue();
    }
}
