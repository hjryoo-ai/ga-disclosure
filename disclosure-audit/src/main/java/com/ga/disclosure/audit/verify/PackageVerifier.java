package com.ga.disclosure.audit.verify;

import com.ga.disclosure.audit.AuditChain;
import com.ga.disclosure.audit.anchor.AnchorRecord;
import com.ga.disclosure.audit.anchor.MerkleTree;
import com.ga.disclosure.audit.chain.ChainBreak;
import com.ga.disclosure.audit.chain.SealChainWalker;
import com.ga.disclosure.audit.tsa.TimestampVerification;
import com.ga.disclosure.audit.tsa.TimestampVerifier;
import com.ga.disclosure.audit.tsa.TrustAnchors;
import com.ga.disclosure.audit.verify.VerifyReport.Conclusion;
import com.ga.disclosure.audit.verify.VerifyReport.Counts;
import com.ga.disclosure.audit.verify.VerifyReport.FileDigest;
import com.ga.disclosure.audit.verify.VerifyReport.Inputs;
import com.ga.disclosure.audit.verify.VerifyReport.SealedAfter;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.ga.disclosure.audit.verify.ReportBuilder.map;

/**
 * {@code verify package <zip> [--receipt] [--tsa-trust]}(지시문 §4, 5 계획 §8.3) — 오프라인, DB·키·네트워크 없음. 입력은 바이트다(파일 읽기는
 * CLI의 몫).
 * <ol>
 *   <li>패키지 내부: 매니페스트 스키마, 엔트리 해시·길이와 {@code files}, canonical JCS 재계산 = {@code hashes.canonical}, PDF·서명본 해시,
 *       봉인 PDF가 서명본의 바이트 접두, 서명 레코드(매니페스트·파일)의 두 해시 결속, 감사 발췌 행별 {@code entry_hash} 재계산(연속은 보지 않는다).</li>
 *   <li>영수증이 있으면: 범위(테넌트·확인서·번호·체인 순번), 덮음({@code covering.sealChainSeq ≥ chainSeq}), 직전 앵커 = 매니페스트 {@code anchor},
 *       봉인 체인 구간 재계산(직전 앵커 머리 → 문서 행 = 매니페스트 해시 → 덮는 앵커 머리), 잎(레코드에서 다시) → 경로 → 루트, 토큰을 신뢰 앵커로.</li>
 * </ol>
 * 결론은 발견이 하나도 없을 때만 낸다: 상한 = 토큰 genTime(외부 증명), 하한 = 직전 앵커의 자체 기록(승인 Q13 대안). 영수증이 없으면 고정 문장
 * {@link Statements#INTERNAL_ONLY}. 신뢰 앵커가 없으면 토큰 검사는 TSA_UNTRUSTED(불일치)다.
 */
public final class PackageVerifier {

    public static final String VERSION = "ga-disclosure-verify/1";
    static final String MANIFEST = "manifest.json";
    static final String AUDIT = "audit.jsonl";
    static final String CANONICAL = "canonical.json";
    static final String PDF = "disclosure.pdf";
    static final String SIGNED_PDF = "disclosure-signed.pdf";

    private PackageVerifier() {
    }

    /**
     * @param receiptJson 영수증 내보내기 바이트(없으면 null)
     * @param trustPem    TSA 신뢰 앵커 PEM(없으면 null — 영수증이 있으면 TSA_UNTRUSTED)
     * @param asOf        검증 시각(보고서 기록용, 판정에 쓰지 않는다)
     */
    public static VerifyReport verify(byte[] zip, byte[] receiptJson, byte[] trustPem, Instant asOf) {
        Objects.requireNonNull(zip, "zip");
        Objects.requireNonNull(asOf, "asOf");
        Map<String, byte[]> entries = EvidenceZip.read(zip);
        if (!entries.keySet().iterator().next().equals(MANIFEST)) {
            throw new VerifyInputException("NOT_AN_EVIDENCE_PACKAGE", "the first entry is not " + MANIFEST);
        }
        JsonNode manifest;
        try {
            manifest = Canonicalizer.parseStrict(new String(entries.get(MANIFEST), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new VerifyInputException("NOT_AN_EVIDENCE_PACKAGE", "manifest.json is not JSON", e);
        }
        ReceiptExport receipt = receiptJson == null ? null : ReceiptExport.parse(receiptJson);
        TrustAnchors trust = trustPem == null ? TrustAnchors.none() : trust(trustPem);

        ReportBuilder r = new ReportBuilder();
        String tenantId = manifest.path("tenantId").asString("");
        ReportBuilder.CheckScope schema = r.check("MANIFEST_SCHEMA");
        List<String> schemaErrors = VerifySchemas.manifest(manifest);
        if (!schemaErrors.isEmpty()) {
            r.finding(FindingCode.PACKAGE_ENTRY_MISMATCH, map("entry", MANIFEST), map("schemaErrors", schemaErrors.size()));
        }
        boolean schemaOk = schema.counted(1).close();
        Inputs inputs = new Inputs(FileDigest.of(zip), receiptJson == null ? null : FileDigest.of(receiptJson),
                trustPem == null ? null : FileDigest.of(trustPem), tenantId.matches("^[A-Z0-9][A-Z0-9_]{0,31}$") ? tenantId : "UNKNOWN", null, null, asOf);
        if (!schemaOk) {
            for (String skipped : List.of("ENTRY_HASHES", "CANONICAL_JCS", "SIGNED_PDF_PREFIX", "SIGNATURE_BINDING", "AUDIT_ENTRIES")) {
                r.skipped(skipped);
            }
            return report(r, inputs, Conclusion.NONE, new Counts(1, entries.size() - 1, 0, 0, 0));
        }

        JsonNode hashes = manifest.get("hashes");
        entryHashes(r, manifest, entries);
        byte[] canonical = entries.get(CANONICAL);
        canonicalJcs(r, hashes, canonical);
        byte[] pdf = entries.get(PDF);
        byte[] signed = entries.get(SIGNED_PDF);
        ReportBuilder.CheckScope prefix = r.check("SIGNED_PDF_PREFIX").counted(1);
        if (pdf == null || signed == null || signed.length <= pdf.length || !Arrays.equals(signed, 0, pdf.length, pdf, 0, pdf.length)) {
            r.finding(FindingCode.SIGNED_PDF_NOT_PREFIXED, map("entry", SIGNED_PDF), map());
        }
        prefix.close();
        signatureBinding(r, manifest, entries);
        int auditRows = auditEntries(r, manifest, entries.get(AUDIT));

        long anchors = 0;
        long receipts = 0;
        Conclusion conclusion = Conclusion.NONE;
        if (receipt == null) {
            for (String skipped : List.of("RECEIPT_SCOPE", "SEAL_CHAIN_SEGMENT", "RECEIPT_PATH", "TSA_TOKEN")) {
                r.skipped(skipped);
            }
            r.statement(Statements.INTERNAL_ONLY);
        } else {
            anchors = receipt.previous() == null ? 1 : 2;
            receipts = 1;
            conclusion = receiptChecks(r, manifest, receipt, trust);
            r.statement(Statements.AUDIT_CONTINUITY);
            if (!r.clean()) {
                conclusion = new Conclusion(null, null, conclusion.tsaTrusted());
            } else {
                r.statement(Statements.existedBefore(conclusion.existedBefore()));
                if (conclusion.sealedAfter() != null) {
                    SealedAfter s = conclusion.sealedAfter();
                    r.statement(Statements.sealedAfter(s.anchorDate(), s.sealChainSeq(), s.recordedAt(), conclusion.existedBefore()));
                }
            }
        }
        return report(r, inputs, conclusion, new Counts(1, entries.size() - 1, auditRows, anchors, receipts));
    }

    private static VerifyReport report(ReportBuilder r, Inputs inputs, Conclusion conclusion, Counts counts) {
        return new VerifyReport(VERSION, VerifyReport.Kind.PACKAGE, inputs, r.checks(), r.findings(), r.statements(), conclusion, counts);
    }

    private static TrustAnchors trust(byte[] pem) {
        try {
            return TrustAnchors.fromPem(pem);
        } catch (IllegalArgumentException e) {
            throw new VerifyInputException("TRUST_INVALID", "the trust bundle is not a PEM certificate list", e);
        }
    }

    private static void entryHashes(ReportBuilder r, JsonNode manifest, Map<String, byte[]> entries) {
        ReportBuilder.CheckScope check = r.check("ENTRY_HASHES");
        List<String> listed = new ArrayList<>();
        for (JsonNode f : manifest.get("files")) {
            String path = f.get("path").asString();
            listed.add(path);
            byte[] bytes = entries.get(path);
            if (bytes == null) {
                r.finding(FindingCode.PACKAGE_ENTRY_MISMATCH, map("entry", path), map("problem", "MISSING"));
                continue;
            }
            String actual = Sha256.of(bytes);
            if (!actual.equals(f.get("sha256").asString()) || bytes.length != f.get("bytes").asLong()) {
                r.finding(FindingCode.PACKAGE_ENTRY_MISMATCH, map("entry", path),
                        map("expected", f.get("sha256").asString(), "actual", actual, "expectedBytes", f.get("bytes").asLong(), "actualBytes", (long) bytes.length));
            }
        }
        List<String> present = new ArrayList<>(entries.keySet());
        present.remove(MANIFEST);
        for (String name : present) {
            if (!listed.contains(name)) {
                r.finding(FindingCode.PACKAGE_ENTRY_MISMATCH, map("entry", name), map("problem", "NOT_LISTED"));
            }
        }
        if (!present.equals(present.stream().sorted().toList()) || !listed.equals(listed.stream().sorted().toList())) {
            r.finding(FindingCode.PACKAGE_ENTRY_MISMATCH, map("entry", "*"), map("problem", "NOT_IN_PATH_ORDER"));
        }
        check.counted(listed.size()).close();
    }

    private static void canonicalJcs(ReportBuilder r, JsonNode hashes, byte[] canonical) {
        ReportBuilder.CheckScope check = r.check("CANONICAL_JCS").counted(1);
        String expected = hashes.get("canonical").asString();
        String recomputed;
        try {
            recomputed = canonical == null ? "" : Sha256.of(Canonicalizer.canonicalize(new String(canonical, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            recomputed = "";
        }
        if (!recomputed.equals(expected) || canonical == null || !Sha256.of(canonical).equals(expected)) {
            r.finding(FindingCode.PACKAGE_ENTRY_MISMATCH, map("entry", CANONICAL), map("expected", expected, "actual", recomputed));
        }
        check.close();
    }

    private static void signatureBinding(ReportBuilder r, JsonNode manifest, Map<String, byte[]> entries) {
        ReportBuilder.CheckScope check = r.check("SIGNATURE_BINDING");
        JsonNode hashes = manifest.get("hashes");
        String doc = hashes.get("canonical").asString();
        String pdf = hashes.get("pdf").asString();
        int n = 0;
        for (JsonNode s : manifest.get("signatures")) {
            n++;
            String id = s.get("signatureId").asString();
            String file = s.get("file").asString();
            boolean bound = doc.equals(s.get("signedDocHash").asString()) && pdf.equals(s.get("signedPdfHash").asString());
            byte[] bytes = entries.get(file);
            if (bytes != null) {
                try {
                    JsonNode record = Canonicalizer.parseStrict(new String(bytes, StandardCharsets.UTF_8));
                    bound &= VerifySchemas.signatureFile(record).isEmpty() && id.equals(record.path("signatureId").asString())
                            && doc.equals(record.path("signedDocHash").asString()) && pdf.equals(record.path("signedPdfHash").asString());
                } catch (RuntimeException e) {
                    bound = false;
                }
            } else {
                bound = false;
            }
            if (!bound) {
                r.finding(FindingCode.SIGNATURE_BINDING_MISMATCH, map("signatureId", id, "file", file), map());
            }
        }
        check.counted(n).close();
    }

    private static int auditEntries(ReportBuilder r, JsonNode manifest, byte[] jsonl) {
        ReportBuilder.CheckScope check = r.check("AUDIT_ENTRIES");
        JsonNode summary = manifest.get("audit");
        TenantId tenant = TenantId.of(manifest.get("tenantId").asString());
        List<String> lines = jsonl == null ? List.of() : new String(jsonl, StandardCharsets.UTF_8).lines().filter(l -> !l.isEmpty()).toList();
        long firstSeq = -1;
        long lastSeq = -1;
        String lastHash = null;
        for (String line : lines) {
            long seq = -1;
            try {
                ObjectNode row = (ObjectNode) Canonicalizer.parseStrict(line);
                seq = row.get("seq").asLong();
                String prev = row.remove("prevHash").asString();
                String stated = row.remove("entryHash").asString();
                String recomputed = AuditChain.entryHash(prev, Canonicalizer.canonicalize(row));
                if (!recomputed.equals(stated) || !tenant.value().equals(row.path("tenantId").asString())
                        || !manifest.get("disclosureId").asString().equals(row.path("targetId").asString())) {
                    r.finding(FindingCode.AUDIT_ENTRY_MISMATCH, map("seq", seq), map("expected", recomputed, "actual", stated));
                }
                firstSeq = firstSeq < 0 ? seq : firstSeq;
                lastSeq = seq;
                lastHash = stated;
            } catch (RuntimeException e) {
                r.finding(FindingCode.AUDIT_ENTRY_MISMATCH, map("seq", seq < 0 ? null : seq), map("problem", "UNREADABLE_ROW"));
            }
        }
        if (lines.size() != summary.get("rows").asInt() || firstSeq != summary.get("fromSeq").asLong() || lastSeq != summary.get("toSeq").asLong()
                || !summary.get("lastEntryHash").asString().equals(lastHash)) {
            r.finding(FindingCode.AUDIT_ENTRY_MISMATCH, map("entry", AUDIT), map("problem", "SUMMARY_DIFFERS"));
        }
        check.counted(lines.size()).close();
        return lines.size();
    }

    private static Conclusion receiptChecks(ReportBuilder r, JsonNode manifest, ReceiptExport receipt, TrustAnchors trust) {
        JsonNode hashes = manifest.get("hashes");
        long chainSeq = hashes.get("chainSeq").asLong();
        TenantId tenant = TenantId.of(manifest.get("tenantId").asString());

        ReportBuilder.CheckScope scope = r.check("RECEIPT_SCOPE").counted(1);
        if (!receipt.tenant().equals(tenant) || !receipt.disclosureId().equals(manifest.get("disclosureId").asString())
                || !receipt.disclosureNo().equals(manifest.get("disclosureNo").asString()) || receipt.chainSeq() != chainSeq) {
            r.finding(FindingCode.RECEIPT_PATH_INVALID, map("receipt", "scope"), map("problem", "ANOTHER_DOCUMENT"));
        }
        if (receipt.covering().sealChainSeq() < chainSeq) {
            r.finding(FindingCode.RECEIPT_NOT_COVERING, map("anchorSeq", receipt.covering().anchorSeq()),
                    map("sealChainSeq", receipt.covering().sealChainSeq(), "chainSeq", chainSeq));
        }
        ReceiptExport.Anchor previous = receipt.previous();
        if (previous != null) {
            JsonNode ref = manifest.get("anchor");
            boolean sameAsManifest = !ref.isNull() && ref.get("anchorSeq").asLong() == previous.anchorSeq()
                    && ref.get("anchorDate").asString().equals(previous.anchorDate().toString()) && ref.get("leafHash").asString().equals(previous.leafHash())
                    && ref.get("sealChainSeq").asLong() == previous.sealChainSeq() && ref.get("auditSeq").asLong() == previous.auditSeq();
            if (!sameAsManifest || previous.sealChainSeq() >= chainSeq || !previous.record(tenant).leafHash().equals(previous.leafHash())) {
                r.finding(FindingCode.RECEIPT_PATH_INVALID, map("anchorSeq", previous.anchorSeq()), map("problem", "PREVIOUS_ANCHOR"));
            }
        }
        boolean scopeOk = scope.close();

        ReportBuilder.CheckScope segment = r.check("SEAL_CHAIN_SEGMENT");
        long coveringSeq = receipt.covering().sealChainSeq();
        SealChainWalker walker = previous == null || !scopeOk ? SealChainWalker.fromGenesis(Set.of(coveringSeq))
                : SealChainWalker.from(previous.sealChainSeq(), previous.sealChainHead(), Set.of(coveringSeq));
        long expectedFirst = previous == null || !scopeOk ? 1 : previous.sealChainSeq() + 1;
        boolean documentRow = false;
        for (ReceiptExport.Link link : receipt.sealChain()) {
            walker.accept(link.seal());
            if (link.chainSeq() == chainSeq) {
                documentRow = link.disclosureNo().equals(manifest.get("disclosureNo").asString())
                        && link.canonicalHash().equals(hashes.get("canonical").asString()) && link.pdfHash().equals(hashes.get("pdf").asString())
                        && link.chainHash().equals(hashes.get("chain").asString());
            }
        }
        for (ChainBreak b : walker.breaks()) {
            r.finding(FindingCode.SEAL_CHAIN_BROKEN, map("chainSeq", b.seq()), map("kind", b.kind().name()));
        }
        if (receipt.sealChain().isEmpty() || receipt.sealChain().getFirst().chainSeq() != expectedFirst || walker.lastSeq() != coveringSeq) {
            r.finding(FindingCode.SEAL_CHAIN_BROKEN, map("chainSeq", coveringSeq), map("problem", "SEGMENT_BOUNDS"));
        } else if (!walker.headAt(coveringSeq).map(h -> h.equals(receipt.covering().sealChainHead())).orElse(false)) {
            r.finding(FindingCode.SEAL_CHAIN_BROKEN, map("chainSeq", coveringSeq), map("problem", "COVERING_HEAD"));
        }
        if (!documentRow) {
            r.finding(FindingCode.RECEIPT_PATH_INVALID, map("chainSeq", chainSeq), map("problem", "DOCUMENT_NOT_IN_SEGMENT"));
        }
        segment.counted(receipt.sealChain().size()).close();

        ReportBuilder.CheckScope path = r.check("RECEIPT_PATH").counted(1);
        AnchorRecord covering = receipt.covering().record(tenant);
        ReceiptExport.Receipt rc = receipt.receipt();
        if (!covering.leafHash().equals(receipt.covering().leafHash())
                || !MerkleTree.verify(covering.canonical(), rc.leafIndex(), rc.merklePath(), rc.treeDepth(), rc.rootHash())) {
            r.finding(FindingCode.RECEIPT_PATH_INVALID, map("anchorSeq", covering.anchorSeq()), map("root", rc.rootHash()));
        }
        path.close();

        ReportBuilder.CheckScope token = r.check("TSA_TOKEN").counted(1);
        Boolean trusted = null;
        Instant genTime = null;
        switch (new TimestampVerifier(trust).verify(rc.tsaToken(), HexFormat.of().parseHex(rc.rootHash()))) {
            case TimestampVerification.Valid v -> {
                trusted = true;
                genTime = v.token().genTime();
                if (!genTime.equals(rc.tsaGenTime()) || !v.token().serialHex().equals(rc.tsaSerial()) || !v.token().policyOid().equals(rc.tsaPolicyOid())) {
                    r.finding(FindingCode.TSA_INVALID, map("anchorSeq", covering.anchorSeq()), map("problem", "RECEIPT_FIELDS_DIFFER"));
                }
            }
            case TimestampVerification.Invalid i -> r.finding(FindingCode.TSA_INVALID, map("anchorSeq", covering.anchorSeq()), map("reason", i.reason()));
            case TimestampVerification.Untrusted u -> {
                trusted = false;
                r.finding(FindingCode.TSA_UNTRUSTED, map("anchorSeq", covering.anchorSeq()), map("reason", u.reason()));
            }
        }
        token.close();
        SealedAfter sealedAfter = previous == null ? null : new SealedAfter(previous.anchorDate(), previous.sealChainSeq(), previous.createdAt());
        return new Conclusion(genTime, sealedAfter, trusted);
    }
}
