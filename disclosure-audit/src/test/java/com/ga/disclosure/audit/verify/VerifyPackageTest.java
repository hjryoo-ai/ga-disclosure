package com.ga.disclosure.audit.verify;

import com.ga.disclosure.audit.tsa.stub.LocalStubTsa;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G5(5 계획 §8.3, DB 없이): 생산자의 실제 빌더로 만든 패키지 + 테스트용 영수증. 정상 0, 변조 각각 2 + 코드, 손상 입력 3, 영수증 유무 문장, 다른 문서
 * 영수증 2, 상한·하한 문장(승인 Q13 대안)을 각각 단언, 신뢰 앵커 없음 → TSA_UNTRUSTED, 보고서는 스키마를 통과한다.
 */
class VerifyPackageTest {

    static final Instant AS_OF = Instant.parse("2026-10-03T00:00:00Z");
    final PackageFixtures f = new PackageFixtures();

    VerifyReport verify(byte[] zip) {
        return PackageVerifier.verify(zip, null, null, AS_OF);
    }

    VerifyReport verify(byte[] zip, ReceiptExport receipt) {
        return PackageVerifier.verify(zip, receipt.canonical(), f.trustPem(), AS_OF);
    }

    static List<FindingCode> codes(VerifyReport r) {
        return r.findings().stream().map(VerifyReport.Finding::code).distinct().toList();
    }

    @Test
    void anUntouchedPackageMatchesAndSaysOnlyInternalConsistencyIsShown() {
        VerifyReport r = verify(f.zip);

        assertThat(r.findings()).isEmpty();
        assertThat(r.exitCode()).isZero();
        assertThat(r.statements()).containsExactly(Statements.INTERNAL_ONLY);
        assertThat(r.conclusion()).isEqualTo(VerifyReport.Conclusion.NONE);
        assertThat(r.checks()).extracting(VerifyReport.Check::check).containsExactly("MANIFEST_SCHEMA", "ENTRY_HASHES", "CANONICAL_JCS",
                "SIGNED_PDF_PREFIX", "SIGNATURE_BINDING", "AUDIT_ENTRIES", "RECEIPT_SCOPE", "SEAL_CHAIN_SEGMENT", "RECEIPT_PATH", "TSA_TOKEN");
        assertThat(r.counts().auditRows()).isEqualTo(3);
        assertThat(VerifySchemas.report(r.toJson())).isEmpty();
        assertThat(r.inputs().tenantId()).isEqualTo("DEMO1");
    }

    @Test
    void withTheReceiptTheUpperBoundIsTheTsaTimeAndTheLowerBoundIsSelfRecorded() {
        VerifyReport r = verify(f.zip, f.receipt());

        assertThat(r.findings()).isEmpty();
        Instant t1 = f.stamp.token().genTime();
        assertThat(r.conclusion().existedBefore()).isEqualTo(t1);
        assertThat(r.conclusion().tsaTrusted()).isTrue();
        assertThat(r.conclusion().sealedAfter()).isEqualTo(new VerifyReport.SealedAfter(f.previous.anchorDate(), 1, f.previousCreatedAt));
        // 상한 문장과 하한 문장(약한 문장)을 각각 단언한다(승인 Q13)
        assertThat(r.statements()).contains("이 문서는 " + t1 + " 이전에 이 내용으로 존재했다.");
        assertThat(r.statements()).contains("이 문서는 2026-09-23 앵커의 봉인 체인 머리(seq 1, 기록 시각 2026-09-22T15:00:05Z) 뒤에 봉인되었다. "
                + "하한의 시각은 자체 기록이며 외부로 증명되는 것은 상한(" + t1 + " 이전)뿐이다.");
        assertThat(r.statements()).contains(Statements.AUDIT_CONTINUITY).doesNotContain(Statements.INTERNAL_ONLY);
        assertThat(r.statements()).noneMatch(s -> s.contains("이후에 존재"));
        assertThat(VerifySchemas.report(r.toJson())).isEmpty();
        assertThat(r.counts().anchors()).isEqualTo(2);
    }

    @Test
    void aTamperedEntryIsAnEntryMismatch() {
        assertThatMismatch(() -> PackageFixtures.zip(retouch(PackageFixtures.entries(f.zip), "canonical.json", b -> {
            b[3] ^= 0x01;
            return b;
        })), FindingCode.PACKAGE_ENTRY_MISMATCH);
    }

    @Test
    void canonicalBytesThatAreNotJcsDifferEvenWithTheSameContent() {
        byte[] zip = PackageFixtures.repack(f.zip, e -> e.put("canonical.json",
                new String(PackageFixtures.CANONICAL, StandardCharsets.UTF_8).replace(",", ", ").getBytes(StandardCharsets.UTF_8)), m -> {
        });
        VerifyReport r = verify(zip);
        assertThat(codes(r)).containsExactly(FindingCode.PACKAGE_ENTRY_MISMATCH);
        assertThat(r.findings().getFirst().where()).containsEntry("entry", "canonical.json");
    }

    @Test
    void aSignedPdfThatDoesNotStartWithTheSealedPdfIsReported() {
        byte[] other = "%PDF-1.7 another document\n%%EOF\n% increment\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        byte[] zip = PackageFixtures.repack(f.zip, e -> e.put("disclosure-signed.pdf", other),
                m -> ((ObjectNode) m.get("hashes")).put("signedPdf", com.ga.platform.canonical.Sha256.of(other)));
        assertThat(codes(verify(zip))).containsExactly(FindingCode.SIGNED_PDF_NOT_PREFIXED);
    }

    @Test
    void aSignatureBoundToAnotherDocumentIsReported() {
        byte[] zip = PackageFixtures.repack(f.zip, e -> e.computeIfPresent("signatures/2-MANAGER.json", (k, v) ->
                new String(v, StandardCharsets.UTF_8).replace(f.canonicalHash, "d".repeat(64)).getBytes(StandardCharsets.UTF_8)), m -> {
        });
        VerifyReport r = verify(zip);
        assertThat(codes(r)).containsExactly(FindingCode.SIGNATURE_BINDING_MISMATCH);
        assertThat(r.findings().getFirst().where()).containsEntry("file", "signatures/2-MANAGER.json");
    }

    @Test
    void anEditedAuditRowIsReportedByItsSeq() {
        byte[] zip = PackageFixtures.repack(f.zip, e -> e.computeIfPresent("audit.jsonl", (k, v) ->
                new String(v, StandardCharsets.UTF_8).replace("\"n\":3", "\"n\":33").getBytes(StandardCharsets.UTF_8)), m -> {
        });
        VerifyReport r = verify(zip);
        assertThat(codes(r)).containsExactly(FindingCode.AUDIT_ENTRY_MISMATCH);
        assertThat(r.findings().getFirst().where()).containsEntry("seq", 4L);
    }

    @Test
    void aManifestOutsideItsSchemaIsAnEntryMismatchAndTheRestIsSkipped() {
        byte[] zip = PackageFixtures.repack(f.zip, e -> {
        }, m -> m.put("note", "added"));
        VerifyReport r = verify(zip);
        assertThat(codes(r)).containsExactly(FindingCode.PACKAGE_ENTRY_MISMATCH);
        assertThat(r.checks()).filteredOn(c -> c.status() == VerifyReport.Status.SKIPPED).hasSize(5);
    }

    @Test
    void anUnlistedExtraEntryIsReported() {
        var e = PackageFixtures.entries(f.zip);
        e.put("z-extra.txt", "x".getBytes(StandardCharsets.US_ASCII));
        VerifyReport r = verify(PackageFixtures.zip(e));
        assertThat(codes(r)).containsExactly(FindingCode.PACKAGE_ENTRY_MISMATCH);
        assertThat(r.findings()).anySatisfy(x -> assertThat(x.detail()).containsEntry("problem", "NOT_LISTED"));
    }

    // ------------------------------------------------------------------ 영수증

    @Test
    void aReceiptForAnotherDocumentIsRejected() {
        ReceiptExport mine = f.receipt();
        ReceiptExport other = new ReceiptExport(mine.tenant(), "00000000-0000-4000-8000-000000000003", "DEMO1-2026-000003", 3, mine.covering(),
                mine.receipt(), null, mine.sealChain());
        assertThat(codes(verify(f.zip, other))).contains(FindingCode.RECEIPT_PATH_INVALID);
    }

    /** G14 "다른 테넌트 영수증 수용": 내용이 같아도 다른 테넌트로 표시된 영수증은 받지 않는다(범위 검사). */
    @Test
    void aReceiptLabelledForAnotherTenantIsRejected() {
        ReceiptExport mine = f.receipt();
        ReceiptExport relabelled = new ReceiptExport(com.ga.platform.core.tenant.TenantId.of("DEMO2"), mine.disclosureId(), mine.disclosureNo(),
                mine.chainSeq(), mine.covering(), mine.receipt(), mine.previous(), mine.sealChain());
        VerifyReport r = verify(f.zip, relabelled);
        assertThat(codes(r)).contains(FindingCode.RECEIPT_PATH_INVALID);
        assertThat(r.findings()).anySatisfy(x -> assertThat(x.detail()).containsEntry("problem", "ANOTHER_DOCUMENT"));
        assertThat(r.conclusion().existedBefore()).isNull();
    }

    @Test
    void aReceiptWhoseAnchorStopsBeforeTheDocumentDoesNotCover() {
        ReceiptExport mine = f.receipt();
        ReceiptExport early = new ReceiptExport(mine.tenant(), mine.disclosureId(), mine.disclosureNo(), mine.chainSeq(),
                PackageFixtures.anchor(f.previous, f.previousCreatedAt), mine.receipt(), null, f.chain.subList(0, 1));
        assertThat(codes(verify(f.zip, early))).contains(FindingCode.RECEIPT_NOT_COVERING);
    }

    @Test
    void aTamperedChainSegmentBreaksTheSealChain() {
        ReceiptExport mine = f.receipt();
        List<ReceiptExport.Link> links = new ArrayList<>(mine.sealChain());
        ReceiptExport.Link third = links.get(1);
        links.set(1, new ReceiptExport.Link(third.chainSeq(), third.disclosureNo(), "f".repeat(64), third.pdfHash(), third.chainHash()));
        VerifyReport r = verify(f.zip, withChain(mine, links));
        assertThat(codes(r)).contains(FindingCode.SEAL_CHAIN_BROKEN);
        assertThat(r.conclusion().existedBefore()).isNull();
    }

    @Test
    void aTamperedPathIsAnInvalidReceiptPath() {
        ReceiptExport mine = f.receipt();
        List<String> path = new ArrayList<>(mine.receipt().merklePath());
        path.set(0, "0".repeat(63) + "1");
        ReceiptExport.Receipt rc = mine.receipt();
        ReceiptExport tampered = new ReceiptExport(mine.tenant(), mine.disclosureId(), mine.disclosureNo(), mine.chainSeq(), mine.covering(),
                new ReceiptExport.Receipt(rc.batchId(), rc.rootHash(), rc.treeDepth(), rc.leafIndex(), path, rc.tsaToken(), rc.tsaGenTime(), rc.tsaPolicyOid(),
                        rc.tsaSerial()), mine.previous(), mine.sealChain());
        assertThat(codes(verify(f.zip, tampered))).containsExactly(FindingCode.RECEIPT_PATH_INVALID);
    }

    @Test
    void withoutTrustAnchorsOrWithAnotherTsaTheTokenIsUntrusted() {
        VerifyReport noTrust = PackageVerifier.verify(f.zip, f.receipt().canonical(), null, AS_OF);
        assertThat(codes(noTrust)).containsExactly(FindingCode.TSA_UNTRUSTED);
        assertThat(noTrust.conclusion().tsaTrusted()).isFalse();
        assertThat(noTrust.conclusion().existedBefore()).isNull();

        byte[] otherTrust = LocalStubTsa.ephemeral(Clock.fixed(AS_OF, ZoneOffset.UTC)).trustAnchors().toPem().getBytes(StandardCharsets.US_ASCII);
        assertThat(codes(PackageVerifier.verify(f.zip, f.receipt().canonical(), otherTrust, AS_OF))).containsExactly(FindingCode.TSA_UNTRUSTED);
    }

    @Test
    void aFlippedTokenByteIsAnInvalidToken() {
        ReceiptExport mine = f.receipt();
        ReceiptExport.Receipt rc = mine.receipt();
        byte[] token = rc.tsaToken();
        token[token.length - 3] ^= 0x01;
        ReceiptExport tampered = new ReceiptExport(mine.tenant(), mine.disclosureId(), mine.disclosureNo(), mine.chainSeq(), mine.covering(),
                new ReceiptExport.Receipt(rc.batchId(), rc.rootHash(), rc.treeDepth(), rc.leafIndex(), rc.merklePath(), token, rc.tsaGenTime(),
                        rc.tsaPolicyOid(), rc.tsaSerial()), mine.previous(), mine.sealChain());
        assertThat(codes(verify(f.zip, tampered))).containsExactly(FindingCode.TSA_INVALID);
    }

    @Test
    void aPreviousAnchorOtherThanTheManifestsIsRejected() {
        ReceiptExport mine = f.receipt();
        ReceiptExport.Anchor p = mine.previous();
        ReceiptExport.Anchor forged = new ReceiptExport.Anchor(p.anchorSeq(), p.anchorDate(), p.sealChainSeq(), p.sealChainHead(), p.auditSeq(),
                p.auditHead(), p.leafHash(), p.createdAt().minusSeconds(86_400));
        ReceiptExport.Anchor otherLeaf = new ReceiptExport.Anchor(p.anchorSeq() + 5, p.anchorDate(), p.sealChainSeq(), p.sealChainHead(), p.auditSeq(),
                p.auditHead(), p.leafHash(), p.createdAt());
        assertThat(codes(verify(f.zip, withPrevious(mine, otherLeaf)))).contains(FindingCode.RECEIPT_PATH_INVALID);
        // 기록 시각은 잎 입력이 아니다 — 자체 기록이라는 문장이 그 한계를 말한다(승인 Q13)
        assertThat(verify(f.zip, withPrevious(mine, forged)).findings()).isEmpty();
    }

    // ------------------------------------------------------------------ 입력 오류(종료 3)

    @Test
    void unreadableInputsAreInputErrorsNotReports() {
        assertThatThrownBy(() -> verify("not a zip".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOfSatisfying(VerifyInputException.class, e -> assertThat(e.code()).isEqualTo("ZIP_CORRUPT"));
        var e = PackageFixtures.entries(f.zip);
        byte[] manifest = e.remove("manifest.json");
        e.put("manifest.json", manifest);
        assertThatThrownBy(() -> verify(PackageFixtures.zip(e)))
                .isInstanceOfSatisfying(VerifyInputException.class, x -> assertThat(x.code()).isEqualTo("NOT_AN_EVIDENCE_PACKAGE"));
        byte[] badReceipt = "{\"exportVersion\":1}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> PackageVerifier.verify(f.zip, badReceipt, null, AS_OF))
                .isInstanceOfSatisfying(VerifyInputException.class, x -> assertThat(x.code()).isEqualTo("RECEIPT_INVALID"));
        byte[] truncated = Arrays.copyOf(f.zip, f.zip.length / 2);
        assertThatThrownBy(() -> verify(truncated)).isInstanceOf(VerifyInputException.class);
    }

    @Test
    void theReceiptExportRoundTripsThroughItsSchema() {
        ReceiptExport r = f.receipt();
        JsonNode json = Canonicalizer.parseStrict(new String(r.canonical(), StandardCharsets.UTF_8));
        assertThat(VerifySchemas.receiptExport(json)).isEmpty();
        assertThat(ReceiptExport.parse(r.canonical()).canonical()).isEqualTo(r.canonical());
    }

    @Test
    void theFindingCodesAreTheSchemasList() {
        JsonNode schema = Canonicalizer.parseStrict(resource("/ga-contracts/verify/v1/verify-report.schema.json"));
        List<String> listed = new ArrayList<>();
        schema.at("/$defs/findingCode/enum").forEach(n -> listed.add(n.asString()));
        assertThat(listed).containsExactlyElementsOf(Arrays.stream(FindingCode.values()).map(Enum::name).toList());
    }

    // ------------------------------------------------------------------ 보조

    private void assertThatMismatch(java.util.function.Supplier<byte[]> zip, FindingCode code) {
        VerifyReport r = verify(zip.get());
        assertThat(r.exitCode()).isEqualTo(VerifyReport.EXIT_MISMATCH);
        assertThat(codes(r)).contains(code);
    }

    private static java.util.Map<String, byte[]> retouch(java.util.Map<String, byte[]> entries, String name, UnaryOperator<byte[]> change) {
        entries.put(name, change.apply(entries.get(name).clone()));
        return entries;
    }

    private static ReceiptExport withChain(ReceiptExport r, List<ReceiptExport.Link> links) {
        return new ReceiptExport(r.tenant(), r.disclosureId(), r.disclosureNo(), r.chainSeq(), r.covering(), r.receipt(), r.previous(), links);
    }

    private static ReceiptExport withPrevious(ReceiptExport r, ReceiptExport.Anchor previous) {
        return new ReceiptExport(r.tenant(), r.disclosureId(), r.disclosureNo(), r.chainSeq(), r.covering(), r.receipt(), previous, r.sealChain());
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IllegalArgumentException("not found");
    }

    private static String resource(String path) {
        try (var in = VerifyPackageTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
