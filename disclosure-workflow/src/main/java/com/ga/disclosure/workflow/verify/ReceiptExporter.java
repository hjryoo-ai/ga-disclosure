package com.ga.disclosure.workflow.verify;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.verify.ReceiptExport;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.seal.evidence.EvidencePackageReader;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.anchor.AnchorReceipt;
import com.ga.disclosure.workflow.anchor.AnchorStore;
import com.ga.disclosure.workflow.anchor.AnchorStore.StoredAnchor;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code anchor receipt export --tenant T --disclosure X}(5 계획 §8.2, 4 수용심사 결정 4). 증거 패키지(감사되는 열람 {@code ARTIFACT_VIEW})에서
 * 매니페스트의 {@code chainSeq}·{@code audit.toSeq}·{@code anchor}를 읽고:
 * <ul>
 *   <li>{@code covering} = 문서 {@code chainSeq} 이상이면서 {@code auditSeq ≥ audit.toSeq}인 첫 <b>고정된</b> 앵커와 그 영수증. 없으면 아직 덮이지
 *       않았다({@code NOT_YET_COVERED}).</li>
 *   <li>{@code previous} = 매니페스트 {@code anchor}가 가리키는 앵커 — 그 봉인 체인 머리가 문서보다 앞설 때만(하한 문장의 재료, 승인 Q13).</li>
 *   <li>{@code sealChain} = {@code previous.sealChainSeq + 1}(없으면 1) … {@code covering.sealChainSeq}(해시만).</li>
 * </ul>
 * 감사 {@code ANCHOR_RECEIPT_EXPORTED}(앵커 순번·내보낸 바이트의 SHA-256). 감사 행은 내보내지 않는다.
 */
public final class ReceiptExporter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public sealed interface Result {
        record Exported(ReceiptExport export, byte[] bytes) implements Result {
        }

        /** {@code PACKAGE_UNAVAILABLE:<사유>}·{@code NOT_SEALED}·{@code NOT_YET_COVERED}. */
        record NotAvailable(String code) implements Result {
        }
    }

    private final SealChainReader chain;
    private final AnchorStore anchors;
    private final ArtifactService artifacts;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;

    public ReceiptExporter(SealChainReader chain, AnchorStore anchors, ArtifactService artifacts, AuditPort audit, WorkflowTransactions transactions,
                           Clock clock) {
        this.chain = Objects.requireNonNull(chain, "chain");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result export(TenantId tenant, Actor actor, DisclosureId id) {
        JsonNode manifest;
        switch (artifacts.view(tenant, actor, id, ArtifactKind.EVIDENCE_ZIP)) {
            case ArtifactService.View.Granted g -> manifest = Canonicalizer.parseStrict(new String(EvidencePackageReader.entries(g.plaintext())
                    .get("manifest.json"), StandardCharsets.UTF_8));
            case ArtifactService.View.Denied d -> {
                return new Result.NotAvailable("PACKAGE_UNAVAILABLE:" + d.reason().name());
            }
        }
        long chainSeq = manifest.get("hashes").get("chainSeq").asLong();
        long toSeq = manifest.get("audit").get("toSeq").asLong();
        JsonNode ref = manifest.get("anchor");
        return transactions.inTenant(tenant, () -> {
            Optional<SealChainReader.ChainRow> row = chain.byDisclosure(id);
            if (row.isEmpty()) {
                return new Result.NotAvailable("NOT_SEALED");
            }
            List<StoredAnchor> all = anchors.all();
            Optional<StoredAnchor> previous = ref.isNull() ? Optional.empty()
                    : all.stream().filter(a -> a.record().anchorSeq() == ref.get("anchorSeq").asLong() && a.record().sealChainSeq() < chainSeq).findFirst();
            for (StoredAnchor candidate : all) {
                if (candidate.record().sealChainSeq() < chainSeq || candidate.record().auditSeq() < toSeq) {
                    continue;
                }
                Optional<AnchorReceipt> receipt = anchors.receipt(candidate.record().anchorSeq());
                if (receipt.isEmpty()) {
                    continue;
                }
                long from = previous.map(p -> p.record().sealChainSeq() + 1).orElse(1L);
                List<ReceiptExport.Link> links = chain.links(from, candidate.record().sealChainSeq()).stream()
                        .map(l -> new ReceiptExport.Link(l.chainSeq(), l.disclosureNo(), l.canonicalHash(), l.pdfHash(), l.chainHash())).toList();
                AnchorReceipt rc = receipt.get();
                ReceiptExport export = new ReceiptExport(tenant, id.toString(), row.get().disclosureNo(), chainSeq, anchor(candidate),
                        new ReceiptExport.Receipt(rc.batchId(), rc.rootHash(), rc.treeDepth(), rc.leafIndex(), rc.merklePath(), rc.tsaToken(), rc.tsaGenTime(),
                                rc.tsaPolicyOid(), rc.tsaSerial()),
                        previous.map(ReceiptExporter::anchor).orElse(null), links);
                byte[] bytes = export.canonical();
                ObjectNode detail = JSON.createObjectNode().put("coveringAnchorSeq", candidate.record().anchorSeq())
                        .put("sealChainFrom", from).put("sealChainTo", candidate.record().sealChainSeq()).put("exportSha256", Sha256.of(bytes));
                if (previous.isPresent()) {
                    detail.put("previousAnchorSeq", previous.get().record().anchorSeq());
                } else {
                    detail.putNull("previousAnchorSeq");
                }
                audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.ANCHOR_RECEIPT_EXPORTED, "DISCLOSURE", id.toString(),
                        detail));
                return new Result.Exported(export, bytes);
            }
            return new Result.NotAvailable("NOT_YET_COVERED");
        });
    }

    private static ReceiptExport.Anchor anchor(StoredAnchor a) {
        return new ReceiptExport.Anchor(a.record().anchorSeq(), a.record().anchorDate(), a.record().sealChainSeq(), a.record().sealChainHead(),
                a.record().auditSeq(), a.record().auditHead(), a.leafHash(), a.createdAt());
    }
}
