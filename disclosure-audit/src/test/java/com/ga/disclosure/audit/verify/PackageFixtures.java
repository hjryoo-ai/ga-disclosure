package com.ga.disclosure.audit.verify;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditChain;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.audit.anchor.AnchorRecord;
import com.ga.disclosure.audit.anchor.MerkleTree;
import com.ga.disclosure.audit.chain.SealChain;
import com.ga.disclosure.audit.tsa.NonceSource;
import com.ga.disclosure.audit.tsa.StampResponse;
import com.ga.disclosure.audit.tsa.TimestampClient;
import com.ga.disclosure.audit.tsa.stub.LocalStubTsa;
import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.seal.evidence.EvidenceInput;
import com.ga.disclosure.seal.evidence.EvidencePackageBuilder;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 검증기 시험 재료(가상, 개인정보 없음): 생산자(seal)의 실제 빌더로 만든 증거 패키지와, 그 문서를 덮는 영수증 내보내기. 모든 값이 서로 맞는다 — canonical은
 * JCS, 감사 발췌는 실제 체인(다른 대상 행과 섞여 seq가 띈다), 봉인 체인 1·2(이 문서)·3, 직전 앵커(seq 1 머리) = 매니페스트 {@code anchor}, 덮는 앵커
 * (seq 3 머리)의 잎은 다른 테넌트 잎과 한 트리(깊이 16), 토큰은 실행마다 만든 스텁 TSA.
 */
final class PackageFixtures {

    static final TenantId TENANT = TenantId.of("DEMO1");
    static final String DISCLOSURE = "00000000-0000-4000-8000-000000000002";
    static final String NUMBER = "DEMO1-2026-000002";
    static final Instant STAMP_AT = Instant.parse("2026-09-24T15:00:07Z");
    static final byte[] CANONICAL = Canonicalizer.canonicalize("{\"canonicalVersion\":1,\"disclosureNo\":\"" + NUMBER + "\",\"tenantId\":\"DEMO1\"}");
    static final byte[] PDF = "%PDF-1.7 sealed original (verify fixture)\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] SIGNED_PDF = concat(PDF, "\n% signature page increment (verify fixture)\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    final LocalStubTsa tsa = LocalStubTsa.ephemeral(Clock.fixed(STAMP_AT, ZoneOffset.UTC));
    final String canonicalHash = Sha256.of(CANONICAL);
    final String pdfHash = Sha256.of(PDF);
    final List<ReceiptExport.Link> chain = new ArrayList<>();
    final List<AuditRecord> auditLog = new ArrayList<>();
    final AnchorRecord previous;
    final AnchorRecord covering;
    final Instant previousCreatedAt = Instant.parse("2026-09-22T15:00:05Z");
    final Instant coveringCreatedAt = Instant.parse("2026-09-24T15:00:05Z");
    final MerkleTree.Tree tree;
    final StampResponse stamp;
    final byte[] zip;

    PackageFixtures() {
        String prev = SealChain.ZERO;
        String[][] docs = {{"DEMO1-2026-000001", "c1", "p1"}, {NUMBER, null, null}, {"DEMO1-2026-000003", "c3", "p3"}};
        for (int i = 0; i < docs.length; i++) {
            String c = docs[i][1] == null ? canonicalHash : Sha256.of(docs[i][1].getBytes(StandardCharsets.US_ASCII));
            String p = docs[i][2] == null ? pdfHash : Sha256.of(docs[i][2].getBytes(StandardCharsets.US_ASCII));
            prev = SealChain.next(prev, c, p);
            chain.add(new ReceiptExport.Link(i + 1, docs[i][0], c, p, prev));
        }
        append("DISCLOSURE", AuditAction.DISCLOSURE_SEAL, "00000000-0000-4000-8000-000000000001");
        previous = new AnchorRecord(TENANT, 1, LocalDate.parse("2026-09-23"), 1, chain.get(0).chainHash(), auditLog.getLast().seq(),
                auditLog.getLast().entryHash());
        append("ANCHOR", AuditAction.ANCHOR_CREATED, "2026-09-23");
        append("DISCLOSURE", AuditAction.DISCLOSURE_SEAL, DISCLOSURE);
        append("DISCLOSURE", AuditAction.SIGNATURE_CAPTURED, DISCLOSURE);
        append("DISCLOSURE", AuditAction.DISCLOSURE_SEAL, "00000000-0000-4000-8000-000000000003");
        append("DISCLOSURE", AuditAction.SIGNATURE_CAPTURED, DISCLOSURE);
        byte[] built = EvidencePackageBuilder.build(input()).zip();
        append("DISCLOSURE", AuditAction.DISCLOSURE_COMPLETED, DISCLOSURE);
        covering = new AnchorRecord(TENANT, 2, LocalDate.parse("2026-09-25"), 3, chain.get(2).chainHash(), auditLog.getLast().seq(),
                auditLog.getLast().entryHash());
        AnchorRecord otherTenant = new AnchorRecord(TenantId.of("DEMO2"), 7, LocalDate.parse("2026-09-25"), 0, SealChain.ZERO, 3, "e".repeat(64));
        tree = MerkleTree.build(List.of(covering.leafHash(), otherTenant.leafHash()), 16);
        stamp = new TimestampClient(tsa, NonceSource.secure(), tsa.trustAnchors()).stamp(HexFormat.of().parseHex(tree.root()));
        zip = built;
    }

    private void append(String kind, AuditAction action, String targetId) {
        AuditEntry e = new AuditEntry(Instant.parse("2026-09-23T01:00:00Z").plusSeconds(auditLog.size() * 60L), "agent-1@test", "AGENT", action, kind,
                targetId, JSON.createObjectNode().put("n", auditLog.size()));
        auditLog.add(AuditChain.next(TENANT, auditLog.isEmpty() ? null : auditLog.getLast(), e));
    }

    private EvidenceInput input() {
        List<EvidenceInput.AuditRow> rows = new ArrayList<>();
        for (AuditRecord r : auditLog) {
            if (r.entry().targetId().equals(DISCLOSURE)) {
                ObjectNode row = (ObjectNode) Canonicalizer.parseStrict(new String(AuditChain.canonicalEntry(TENANT, r.seq(), r.entry()),
                        StandardCharsets.UTF_8));
                row.put("prevHash", r.prevHash()).put("entryHash", r.entryHash());
                rows.add(new EvidenceInput.AuditRow(r.seq(), row, r.entryHash()));
            }
        }
        EvidenceInput.SignatureRecord customer = new EvidenceInput.SignatureRecord("00000000-0000-4000-8000-0000000000c1", SignerRole.CUSTOMER,
                SignatureChannel.TOUCH_PAD, SignatureMethod.DRAWN, Instant.parse("2026-09-24T01:12:00Z"), canonicalHash, pdfHash,
                "00000000-0000-4000-8000-0000000000e1",
                List.of(new EvidenceInput.IdentityCheckEntry(IdentityMethod.AGENT_FACE_TO_FACE, true, Instant.parse("2026-09-24T01:10:00Z"))),
                Canonicalizer.parseStrict("{\"pages\":3,\"scrollComplete\":true}"), List.of(), null,
                Canonicalizer.parseStrict("{\"fingerprint\":\"fp-demo-1\",\"userAgent\":\"demo-tablet\"}"), "203.0.113.10",
                List.of(new EvidenceInput.EvidenceObject("IMAGE", "9".repeat(64), "8".repeat(64), 1024)));
        EvidenceInput.SignatureRecord manager = new EvidenceInput.SignatureRecord("00000000-0000-4000-8000-0000000000c3", SignerRole.MANAGER,
                SignatureChannel.SSO, SignatureMethod.SSO_APPROVAL, Instant.parse("2026-09-24T05:40:00Z"), canonicalHash, pdfHash, null, List.of(), null,
                List.of(), null, null, null, List.of());
        return new EvidenceInput(TENANT.value(), DISCLOSURE, NUMBER, 1, CANONICAL, PDF, SIGNED_PDF, chain.get(1).chainHash(), 2,
                new EvidenceInput.Pinned("DISC-2026-07", "a".repeat(64), null, null, "STANDARD", 1, "b".repeat(64)),
                new EvidenceInput.Snapshot("GRD-0000001", "GRADING-2026-07", "RANK-2026-07", "SHARED_RANK", Instant.parse("2026-09-23T00:30:00Z")),
                Instant.parse("2026-09-23T01:00:00Z"), Instant.parse("2026-09-24T05:40:00Z"), LocalDate.parse("2031-09-24"), List.of(customer, manager),
                rows, new EvidenceInput.AnchorRef(previous.anchorSeq(), previous.anchorDate(), previous.leafHash(), previous.sealChainSeq(),
                previous.auditSeq()), 2);
    }

    ReceiptExport receipt() {
        MerkleTree.Proof proof = tree.proof(covering.leafHash());
        return new ReceiptExport(TENANT, DISCLOSURE, NUMBER, 2, anchor(covering, coveringCreatedAt),
                new ReceiptExport.Receipt(UUID.fromString("00000000-0000-4000-8000-0000000000b1"), tree.root(), 16, proof.leafIndex(), proof.siblings(),
                        stamp.tokenDer(), stamp.token().genTime(), stamp.token().policyOid(), stamp.token().serialHex()),
                anchor(previous, previousCreatedAt), chain.subList(1, 3));
    }

    static ReceiptExport.Anchor anchor(AnchorRecord a, Instant createdAt) {
        return new ReceiptExport.Anchor(a.anchorSeq(), a.anchorDate(), a.sealChainSeq(), a.sealChainHead(), a.auditSeq(), a.auditHead(), a.leafHash(),
                createdAt);
    }

    byte[] trustPem() {
        return tsa.trustAnchors().toPem().getBytes(StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------ 변조 도구

    static Map<String, byte[]> entries(byte[] zip) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), in.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * 공격자처럼 고친다: 엔트리를 바꾸고 매니페스트 {@code files}를 새 해시로 다시 쓴 뒤 매니페스트도 고친다 — 엔트리 해시 검사는 통과하고 그 뒤의 검사만
     * 남는다.
     */
    static byte[] repack(byte[] zip, Consumer<Map<String, byte[]>> entries, Consumer<ObjectNode> manifest) {
        Map<String, byte[]> e = entries(zip);
        entries.accept(e);
        ObjectNode m = (ObjectNode) Canonicalizer.parseStrict(new String(e.get("manifest.json"), StandardCharsets.UTF_8));
        var files = m.putArray("files");
        e.keySet().stream().filter(k -> !k.equals("manifest.json")).sorted()
                .forEach(k -> files.addObject().put("path", k).put("sha256", Sha256.of(e.get(k))).put("bytes", e.get(k).length));
        manifest.accept(m);
        e.put("manifest.json", Canonicalizer.canonicalize(m));
        return zip(e);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
