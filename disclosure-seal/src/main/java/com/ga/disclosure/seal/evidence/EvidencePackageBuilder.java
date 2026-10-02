package com.ga.disclosure.seal.evidence;

import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 증거 패키지 빌더(4 계획 §4). 엔트리: {@code manifest.json}(JCS) → 경로 순 {@code audit.jsonl}·{@code canonical.json}·{@code disclosure.pdf}·
 * {@code disclosure-signed.pdf}·{@code signatures/{순번}-{역할}.json}. 매니페스트 {@code files}가 나머지 엔트리 전부의 해시를 경로 순으로 담는다.
 * 빌더는 입력 정합을 먼저 검사한다: 서명의 두 귀속 해시 = canonical·봉인 PDF 해시, 서명본의 접두 = 봉인 PDF, 감사 행 seq 증가. 매니페스트와 서명
 * 파일은 스키마 검증을 통과해야 한다. ZIP은 STORED(압축 없음 — Deflate 출력은 플랫폼 zlib에 따라 다를 수 있다), DOS 시각 고정,
 * extra 필드·주석 없음({@link #ENTRY_TIME}) — 같은 입력이면 어느 환경에서든 같은 바이트다.
 */
public final class EvidencePackageBuilder {

    static final String MANIFEST = "manifest.json";
    static final String AUDIT = "audit.jsonl";
    static final String CANONICAL = "canonical.json";
    static final String PDF = "disclosure.pdf";
    static final String SIGNED_PDF = "disclosure-signed.pdf";
    /**
     * 엔트리 DOS 시각 1980-01-01 00:00:02. 00:00:00은 JDK가 "1980 이전" 표지로 쓰는 값과 같아서, 그 값이면 {@code ZipEntry}가 시스템 시간대로
     * 계산한 확장 타임스탬프 extra 필드를 붙인다 — 실행 환경의 시간대가 바이트에 들어간다(G8 테스트로 확인).
     */
    static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 1, 1, 0, 0, 2);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private EvidencePackageBuilder() {
    }

    public static EvidencePackage build(EvidenceInput in) {
        String canonicalHash = sha256(in.canonicalJson());
        String pdfHash = sha256(in.pdf());
        byte[] signedPdf = in.signedPdf();
        byte[] pdf = in.pdf();
        if (signedPdf.length <= pdf.length || !Arrays.equals(signedPdf, 0, pdf.length, pdf, 0, pdf.length)) {
            throw new IllegalArgumentException("signed PDF must keep the sealed PDF as a byte prefix");
        }
        if (in.signatures().isEmpty() || in.audit().isEmpty()) {
            throw new IllegalArgumentException("an evidence package needs signatures and audit rows");
        }
        Map<String, byte[]> entries = new TreeMap<>();
        entries.put(CANONICAL, in.canonicalJson());
        entries.put(PDF, pdf);
        entries.put(SIGNED_PDF, signedPdf);
        entries.put(AUDIT, auditLines(in.audit()));

        ArrayNode signatures = JSON.arrayNode();
        int n = 1;
        for (EvidenceInput.SignatureRecord s : in.signatures()) {
            if (!s.signedDocHash().equals(canonicalHash) || !s.signedPdfHash().equals(pdfHash)) {
                throw new IllegalArgumentException("signature " + s.signatureId() + " is not bound to this document's hashes");
            }
            String file = "signatures/" + n++ + "-" + s.role().name() + ".json";
            ObjectNode signatureFile = signatureFile(s);
            requireValid(EvidenceManifestSchema.validateSignatureFile(signatureFile), file);
            entries.put(file, Canonicalizer.canonicalize(signatureFile));
            ObjectNode entry = signatures.addObject();
            entry.put("file", file);
            common(entry, s);
            ArrayNode evidence = entry.putArray("evidence");
            for (EvidenceInput.EvidenceObject e : s.evidence()) {
                evidence.addObject().put("kind", e.kind()).put("sha256", e.sha256()).put("cipherSha256", e.cipherSha256()).put("bytes", e.bytes());
            }
        }

        ObjectNode manifest = JSON.objectNode();
        manifest.put("manifestVersion", 1);
        manifest.put("tenantId", in.tenantId());
        manifest.put("disclosureId", in.disclosureId());
        manifest.put("disclosureNo", in.disclosureNo());
        manifest.put("version", in.version());
        ObjectNode hashes = manifest.putObject("hashes");
        hashes.put("canonical", canonicalHash).put("pdf", pdfHash).put("signedPdf", sha256(signedPdf)).put("chain", in.chainHash())
                .put("chainSeq", in.chainSeq());
        EvidenceInput.Pinned p = in.pinned();
        ObjectNode pinned = manifest.putObject("pinned");
        pinned.put("ruleVersionId", p.ruleVersionId()).put("ruleBundleHash", p.ruleBundleHash());
        putNullable(pinned, "tenantRuleVersionId", p.tenantRuleVersionId());
        putNullable(pinned, "tenantRuleBundleHash", p.tenantRuleBundleHash());
        pinned.put("templateId", p.templateId()).put("templateVersion", p.templateVersion()).put("templateBundleHash", p.templateBundleHash());
        EvidenceInput.Snapshot sn = in.snapshot();
        manifest.putObject("snapshot").put("snapshotId", sn.snapshotId()).put("gradingPolicyVersionId", sn.gradingPolicyVersionId())
                .put("rankingPolicyVersionId", sn.rankingPolicyVersionId()).put("tieBreak", sn.tieBreak())
                .put("generatedAt", sn.generatedAt().toString());
        manifest.put("sealedAt", in.sealedAt().toString());
        manifest.put("completedAt", in.completedAt().toString());
        manifest.put("retentionUntil", in.retentionUntil().toString());
        manifest.set("signatures", signatures);
        List<EvidenceInput.AuditRow> audit = in.audit();
        manifest.putObject("audit").put("file", AUDIT).put("fromSeq", audit.getFirst().seq()).put("toSeq", audit.getLast().seq())
                .put("rows", audit.size()).put("lastEntryHash", audit.getLast().entryHash());
        manifest.putNull("anchor");
        ArrayNode files = manifest.putArray("files");
        entries.forEach((path, bytes) -> files.addObject().put("path", path).put("sha256", sha256(bytes)).put("bytes", bytes.length));
        requireValid(EvidenceManifestSchema.validateManifest(manifest), MANIFEST);
        byte[] manifestBytes = Canonicalizer.canonicalize(manifest);

        byte[] zip = zip(manifestBytes, entries);
        return new EvidencePackage(zip, sha256(zip), sha256(manifestBytes));
    }

    private static ObjectNode signatureFile(EvidenceInput.SignatureRecord s) {
        ObjectNode o = JSON.objectNode();
        common(o, s);
        o.set("viewEvidence", s.viewEvidence() == null ? JSON.nullNode() : s.viewEvidence().deepCopy());
        ArrayNode acks = o.putArray("acknowledgedFlags");
        s.acknowledgedFlags().forEach(acks::add);
        o.set("scanMatch", s.scanMatch() == null ? JSON.nullNode() : s.scanMatch().deepCopy());
        o.set("device", s.device() == null ? JSON.nullNode() : s.device().deepCopy());
        putNullable(o, "ip", s.ip());
        return o;
    }

    /** 매니페스트 항목과 서명 파일이 공유하는 필드. */
    private static void common(ObjectNode o, EvidenceInput.SignatureRecord s) {
        o.put("signatureId", s.signatureId());
        o.put("role", s.role().name());
        o.put("channel", s.channel().name());
        o.put("method", s.method().name());
        o.put("signedAt", s.signedAt().toString());
        o.put("signedDocHash", s.signedDocHash());
        o.put("signedPdfHash", s.signedPdfHash());
        putNullable(o, "sessionId", s.sessionId());
        ArrayNode identity = o.putArray("identityCheck");
        for (EvidenceInput.IdentityCheckEntry r : s.identityCheck()) {
            identity.addObject().put("type", r.type().name()).put("result", r.passed() ? "PASS" : "FAIL").put("at", r.at().toString());
        }
    }

    /** 감사 행 JCS + 줄바꿈(seq 엄격 증가). */
    private static byte[] auditLines(List<EvidenceInput.AuditRow> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long previous = Long.MIN_VALUE;
        for (EvidenceInput.AuditRow r : rows) {
            if (r.seq() <= previous) {
                throw new IllegalArgumentException("audit rows must be in strictly increasing seq order");
            }
            previous = r.seq();
            out.writeBytes(Canonicalizer.canonicalize(r.row()));
            out.write('\n');
        }
        return out.toByteArray();
    }

    private static byte[] zip(byte[] manifest, Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.setMethod(ZipOutputStream.STORED);
            put(zip, MANIFEST, manifest);
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                put(zip, e.getKey(), e.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        CRC32 crc = new CRC32();
        crc.update(content);
        entry.setCrc(crc.getValue());
        entry.setTimeLocal(ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static void putNullable(ObjectNode o, String key, String value) {
        if (value == null) {
            o.putNull(key);
        } else {
            o.put(key, value);
        }
    }

    private static void requireValid(List<String> problems, String what) {
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(what + " violates the evidence manifest schema: " + problems);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 패키지에 들어 있는 엔트리 이름 집합 확인용(테스트·검증기). */
    static List<String> fixedEntryNames() {
        return List.of(MANIFEST, AUDIT, CANONICAL, PDF, SIGNED_PDF);
    }

    static JsonNode parse(byte[] json) {
        return Canonicalizer.parseStrict(new String(json, StandardCharsets.UTF_8));
    }
}
