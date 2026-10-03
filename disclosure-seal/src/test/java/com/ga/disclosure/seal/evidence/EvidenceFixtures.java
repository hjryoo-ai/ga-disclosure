package com.ga.disclosure.seal.evidence;

import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.platform.canonical.Canonicalizer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 증거 패키지 시험 재료(가상). 빌더는 PDF를 해석하지 않고 해시·접두만 보므로 PDF 자리에 짧은 바이트를 쓴다. 서명 이미지 원본은 패키지에
 * 들어가면 안 되므로 센티널 바이트로 해시만 만든다.
 */
final class EvidenceFixtures {

    static final byte[] CANONICAL = "{\"canonicalVersion\":1,\"tenantId\":\"DEMO1\"}".getBytes(StandardCharsets.UTF_8);
    static final byte[] PDF = "%PDF-1.7 sealed original (fixture)\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] INCREMENT = "\n% signature page increment (fixture)\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] IMAGE_SENTINEL = "SENTINEL-SIGNATURE-IMAGE-89504E47".getBytes(StandardCharsets.US_ASCII);
    static final String DISCLOSURE = "00000000-0000-4000-8000-000000000001";

    private EvidenceFixtures() {
    }

    static byte[] signedPdf() {
        byte[] out = new byte[PDF.length + INCREMENT.length];
        System.arraycopy(PDF, 0, out, 0, PDF.length);
        System.arraycopy(INCREMENT, 0, out, PDF.length, INCREMENT.length);
        return out;
    }

    static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }

    static EvidenceInput.SignatureRecord customer(String docHash, String pdfHash) {
        return new EvidenceInput.SignatureRecord("00000000-0000-4000-8000-0000000000c1", SignerRole.CUSTOMER, SignatureChannel.TOUCH_PAD,
                SignatureMethod.DRAWN, Instant.parse("2026-09-24T01:12:00Z"), docHash, pdfHash, "00000000-0000-4000-8000-0000000000e1",
                List.of(new EvidenceInput.IdentityCheckEntry(IdentityMethod.AGENT_FACE_TO_FACE, true, Instant.parse("2026-09-24T01:10:00Z")),
                        new EvidenceInput.IdentityCheckEntry(IdentityMethod.SCROLL_COMPLETE, true, Instant.parse("2026-09-24T01:11:00Z"))),
                Canonicalizer.parseStrict("{\"pages\":3,\"scrollComplete\":true}"), List.of(), null,
                Canonicalizer.parseStrict("{\"fingerprint\":\"fp-demo-1\",\"userAgent\":\"demo-tablet\"}"), "203.0.113.10",
                List.of(new EvidenceInput.EvidenceObject("IMAGE", EvidencePackageBuilder.sha256(IMAGE_SENTINEL), hash('9'), IMAGE_SENTINEL.length),
                        new EvidenceInput.EvidenceObject("STROKES", hash('8'), hash('7'), 512)));
    }

    static EvidenceInput.SignatureRecord manager(String docHash, String pdfHash) {
        return new EvidenceInput.SignatureRecord("00000000-0000-4000-8000-0000000000c3", SignerRole.MANAGER, SignatureChannel.SSO,
                SignatureMethod.SSO_APPROVAL, Instant.parse("2026-09-24T05:40:00Z"), docHash, pdfHash, null, List.of(), null,
                List.of("00000000-0000-4000-8000-0000000000f1"), null, null, null, List.of());
    }

    static List<EvidenceInput.AuditRow> audit() {
        return List.of(
                new EvidenceInput.AuditRow(101, Canonicalizer.parseStrict("{\"action\":\"DISCLOSURE_SEAL\",\"entryHash\":\"" + hash('1')
                        + "\",\"seq\":101,\"targetId\":\"" + DISCLOSURE + "\"}"), hash('1')),
                new EvidenceInput.AuditRow(140, Canonicalizer.parseStrict("{\"action\":\"SIGN\",\"entryHash\":\"" + hash('2')
                        + "\",\"seq\":140,\"targetId\":\"" + DISCLOSURE + "\"}"), hash('2')));
    }

    static EvidenceInput input() {
        String doc = EvidencePackageBuilder.sha256(CANONICAL);
        String pdf = EvidencePackageBuilder.sha256(PDF);
        return input(List.of(customer(doc, pdf), manager(doc, pdf)), audit(), signedPdf());
    }

    static EvidenceInput input(List<EvidenceInput.SignatureRecord> signatures, List<EvidenceInput.AuditRow> audit, byte[] signedPdf) {
        return new EvidenceInput("DEMO1", DISCLOSURE, "DEMO1-2026-000001", 1, CANONICAL, PDF, signedPdf, hash('c'), 1,
                new EvidenceInput.Pinned("DISC-2026-07", hash('a'), null, null, "STANDARD", 1, hash('b')),
                new EvidenceInput.Snapshot("GRD-0000001", "GRADING-2026-07", "RANK-2026-07", "SHARED_RANK", Instant.parse("2026-09-23T00:30:00Z")),
                Instant.parse("2026-09-23T01:00:00Z"), Instant.parse("2026-09-24T05:40:00Z"), LocalDate.parse("2031-09-24"), signatures, audit, null);
    }
}
