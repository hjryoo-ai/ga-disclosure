package com.ga.disclosure.audit.verify;

import com.ga.disclosure.audit.anchor.AnchorRecord;
import com.ga.disclosure.audit.chain.SealLink;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 영수증 내보내기(스키마 {@code contracts/verify/v1/anchor-receipt-export.schema.json}, 5 계획 §8.2): 문서를 덮는 첫 앵커와 그 영수증, 매니페스트가
 * 가리키는 직전 앵커(문서보다 앞설 때만), 그 사이 봉인 체인 구간(해시만). 감사 행·개인정보는 없다.
 */
public record ReceiptExport(TenantId tenant, String disclosureId, String disclosureNo, long chainSeq, Anchor covering, Receipt receipt, Anchor previous,
                            List<Link> sealChain) {

    public static final int EXPORT_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public ReceiptExport {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(covering, "covering");
        Objects.requireNonNull(receipt, "receipt");
        sealChain = List.copyOf(sealChain);
    }

    /** 앵커 행(내보내기 표현). */
    public record Anchor(long anchorSeq, LocalDate anchorDate, long sealChainSeq, String sealChainHead, long auditSeq, String auditHead, String leafHash,
                         Instant createdAt) {
        public AnchorRecord record(TenantId tenant) {
            return new AnchorRecord(tenant, anchorSeq, anchorDate, sealChainSeq, sealChainHead, auditSeq, auditHead);
        }
    }

    public record Receipt(UUID batchId, String rootHash, int treeDepth, int leafIndex, List<String> merklePath, byte[] tsaToken, Instant tsaGenTime,
                          String tsaPolicyOid, String tsaSerial) {
        public Receipt {
            merklePath = List.copyOf(merklePath);
            tsaToken = tsaToken.clone();
        }

        @Override
        public byte[] tsaToken() {
            return tsaToken.clone();
        }
    }

    /** 봉인 체인 구간의 한 고리(파기된 확인서도 묘비 해시로 남는다). */
    public record Link(long chainSeq, String disclosureNo, String canonicalHash, String pdfHash, String chainHash) {
        public SealLink seal() {
            return new SealLink(chainSeq, canonicalHash, pdfHash, chainHash);
        }
    }

    /** 바이트를 읽는다. JSON·스키마 위반은 입력 오류(종료 3). */
    public static ReceiptExport parse(byte[] json) {
        JsonNode n;
        try {
            n = Canonicalizer.parseStrict(new String(json, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new VerifyInputException("RECEIPT_INVALID", "receipt is not JSON", e);
        }
        List<String> errors = VerifySchemas.receiptExport(n);
        if (!errors.isEmpty()) {
            throw new VerifyInputException("RECEIPT_INVALID", "receipt violates its schema (" + errors.size() + " errors)");
        }
        List<Link> links = new ArrayList<>();
        for (JsonNode l : n.get("sealChain")) {
            links.add(new Link(l.get("chainSeq").asLong(), l.get("disclosureNo").asString(), l.get("canonicalHash").asString(),
                    l.get("pdfHash").asString(), l.get("chainHash").asString()));
        }
        JsonNode previous = n.get("previous");
        return new ReceiptExport(TenantId.of(n.get("tenantId").asString()), n.get("disclosureId").asString(), n.get("disclosureNo").asString(),
                n.get("chainSeq").asLong(), anchor(n.get("covering").get("anchor")), receipt(n.get("covering").get("receipt")),
                previous.isNull() ? null : anchor(previous.get("anchor")), links);
    }

    public ObjectNode toJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("exportVersion", EXPORT_VERSION);
        n.put("tenantId", tenant.value());
        n.put("disclosureId", disclosureId);
        n.put("disclosureNo", disclosureNo);
        n.put("chainSeq", chainSeq);
        ObjectNode c = n.putObject("covering");
        c.set("anchor", anchorJson(covering));
        ObjectNode r = c.putObject("receipt");
        r.put("batchId", receipt.batchId().toString()).put("rootHash", receipt.rootHash()).put("treeDepth", receipt.treeDepth())
                .put("leafIndex", receipt.leafIndex());
        ArrayNode path = r.putArray("merklePath");
        receipt.merklePath().forEach(path::add);
        r.put("tsaToken", Base64.getEncoder().encodeToString(receipt.tsaToken())).put("tsaGenTime", receipt.tsaGenTime().toString())
                .put("tsaPolicyOid", receipt.tsaPolicyOid()).put("tsaSerial", receipt.tsaSerial());
        if (previous == null) {
            n.putNull("previous");
        } else {
            n.putObject("previous").set("anchor", anchorJson(previous));
        }
        ArrayNode chain = n.putArray("sealChain");
        sealChain.forEach(l -> chain.addObject().put("chainSeq", l.chainSeq()).put("disclosureNo", l.disclosureNo())
                .put("canonicalHash", l.canonicalHash()).put("pdfHash", l.pdfHash()).put("chainHash", l.chainHash()));
        return n;
    }

    /** JCS 바이트(파일로 내보내는 표현). */
    public byte[] canonical() {
        return Canonicalizer.canonicalize(toJson());
    }

    private static ObjectNode anchorJson(Anchor a) {
        return JSON.createObjectNode().put("anchorSeq", a.anchorSeq()).put("anchorDate", a.anchorDate().toString())
                .put("sealChainSeq", a.sealChainSeq()).put("sealChainHead", a.sealChainHead()).put("auditSeq", a.auditSeq())
                .put("auditHead", a.auditHead()).put("leafHash", a.leafHash()).put("createdAt", a.createdAt().toString());
    }

    private static Anchor anchor(JsonNode a) {
        return new Anchor(a.get("anchorSeq").asLong(), LocalDate.parse(a.get("anchorDate").asString()), a.get("sealChainSeq").asLong(),
                a.get("sealChainHead").asString(), a.get("auditSeq").asLong(), a.get("auditHead").asString(), a.get("leafHash").asString(),
                Instant.parse(a.get("createdAt").asString()));
    }

    private static Receipt receipt(JsonNode r) {
        List<String> path = new ArrayList<>();
        r.get("merklePath").forEach(p -> path.add(p.asString()));
        return new Receipt(UUID.fromString(r.get("batchId").asString()), r.get("rootHash").asString(), r.get("treeDepth").asInt(),
                r.get("leafIndex").asInt(), path, Base64.getDecoder().decode(r.get("tsaToken").asString()), Instant.parse(r.get("tsaGenTime").asString()),
                r.get("tsaPolicyOid").asString(), r.get("tsaSerial").asString());
    }
}
