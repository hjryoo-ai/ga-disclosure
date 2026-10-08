package com.ga.disclosure.workflow.verify;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditChainWalker;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.audit.anchor.AnchorRecord;
import com.ga.disclosure.audit.anchor.MerkleTree;
import com.ga.disclosure.audit.chain.ChainBreak;
import com.ga.disclosure.audit.chain.SealChainWalker;
import com.ga.disclosure.audit.tsa.TimestampVerification;
import com.ga.disclosure.audit.tsa.TimestampVerifier;
import com.ga.disclosure.audit.tsa.TrustAnchors;
import com.ga.disclosure.audit.verify.FindingCode;
import com.ga.disclosure.audit.verify.ReportBuilder;
import com.ga.disclosure.audit.verify.VerifyReport;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.anchor.AnchorReceipt;
import com.ga.disclosure.workflow.anchor.AnchorStore.StoredAnchor;
import com.ga.disclosure.workflow.anchor.AnchorStore;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.artifact.SignatureEvidenceRecord;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import static com.ga.disclosure.audit.verify.ReportBuilder.map;

/**
 * {@code verify tenant}(지시문 §4, 5 계획 §8.4). 읽기는 REPEATABLE READ 트랜잭션 하나(한 스냅샷)에서 흘려 읽는다 — 감사·봉인 체인은 페이지 단위,
 * 앵커 대조용 머리는 checkpoint seq에서 다시 계산한 값만 기억한다. 순서: 감사 체인 → 봉인 체인 → 채번(연도별) → 파기 감사({@code destroyed_at} ↔
 * {@code DISCLOSURE_DESTROYED}) → 객체(복호화·평문 해시, 파기 건은 버전·마커 0) → 앵커(잎·두 머리) → 영수증(경로·루트·토큰) → 미고정 기간.
 *
 * <p>쓰기는 끝에 별도 트랜잭션 하나: 감사 {@code VERIFY_RUN}(보고서 해시)과, 무결성 불일치가 있으면 {@code CHAIN_BROKEN} 플래그 — 끊긴 지점의
 * 확인서마다, 확인서를 정할 수 없으면 {@code disclosure_id NULL}로 그 지점을 대상으로(감사 체인 = {@code AUDIT_LOG}·seq, 앵커·영수증 =
 * {@code ANCHOR}·앵커 순번, 그 밖 = {@code TENANT}, 5 계획 §1.6). {@code ANCHOR_UNSTAMPED}·{@code TSA_UNTRUSTED}·{@code ANCHOR_MISSING_DAY}는 무결성이 아니라 운영·설정 신호라
 * 보고서는 불일치(종료 2)지만 플래그를 올리지 않는다.
 */
public final class TenantVerifier {

    public static final int PAGE = 500;
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Set<FindingCode> OPERATIONAL = Set.of(FindingCode.ANCHOR_UNSTAMPED, FindingCode.TSA_UNTRUSTED, FindingCode.ANCHOR_MISSING_DAY);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuditPort audit;
    private final SealChainReader chain;
    private final AnchorStore anchors;
    private final DocumentRecordStore records;
    private final DocumentCryptoPort crypto;
    private final ArtifactStore storage;
    private final RuleResolver rules;
    private final DisclosureFlagPort flags;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final AuthorizationPort authz;

    public TenantVerifier(AuditPort audit, SealChainReader chain, AnchorStore anchors, DocumentRecordStore records, DocumentCryptoPort crypto,
                          ArtifactStore storage, RuleResolver rules, DisclosureFlagPort flags, WorkflowTransactions transactions, Clock clock,
                          AuthorizationPort authz) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.chain = Objects.requireNonNull(chain, "chain");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.records = Objects.requireNonNull(records, "records");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authz = Objects.requireNonNull(authz, "authz");
    }

    /**
     * @param trustPem TSA 신뢰 앵커 PEM(없으면 null — 영수증 토큰은 TSA_UNTRUSTED)
     */
    @UseCaseEntry(Action.VERIFY_TENANT)
    public VerifyReport run(Caller caller, byte[] trustPem) {
        TenantId tenant = caller.tenant();
        Actor actor = transactions.inTenant(tenant, () -> authz.require(caller, Action.VERIFY_TENANT, Target.none()));
        Instant asOf = clock.instant();
        TrustAnchors trust = trustPem == null ? TrustAnchors.none() : TrustAnchors.fromPem(trustPem);
        Read read = transactions.inTenantRepeatableRead(tenant, () -> read(tenant, trust, asOf));
        VerifyReport report = new VerifyReport(com.ga.disclosure.audit.verify.PackageVerifier.VERSION, VerifyReport.Kind.TENANT,
                new VerifyReport.Inputs(null, null, trustPem == null ? null : VerifyReport.FileDigest.of(trustPem), tenant.value(), null, null, asOf),
                read.builder().checks(), read.builder().findings(), read.builder().statements(), VerifyReport.Conclusion.NONE, read.counts());
        transactions.inTenant(tenant, () -> {
            record(tenant, actor, report);
            return null;
        });
        return report;
    }

    private record Read(ReportBuilder builder, VerifyReport.Counts counts) {
    }

    private Read read(TenantId tenant, TrustAnchors trust, Instant asOf) {
        ReportBuilder r = new ReportBuilder();
        List<StoredAnchor> all = anchors.all();
        Set<Long> auditCheckpoints = new HashSet<>();
        Set<Long> sealCheckpoints = new HashSet<>();
        all.forEach(a -> {
            auditCheckpoints.add(a.record().auditSeq());
            sealCheckpoints.add(a.record().sealChainSeq());
        });

        // 1. 감사 체인
        ReportBuilder.CheckScope auditCheck = r.check("AUDIT_CHAIN");
        AuditChainWalker auditWalker = new AuditChainWalker(auditCheckpoints);
        long auditRows = 0;
        Set<String> destroyedAudited = new HashSet<>();
        for (List<AuditRecord> page = audit.readAfter(0, PAGE); !page.isEmpty(); page = audit.readAfter(page.getLast().seq(), PAGE)) {
            for (AuditRecord rec : page) {
                auditWalker.accept(rec);
                if (rec.entry().action() == AuditAction.DISCLOSURE_DESTROYED) {
                    destroyedAudited.add(rec.entry().targetId());
                }
            }
            auditRows += page.size();
        }
        for (ChainBreak b : auditWalker.breaks()) {
            r.finding(FindingCode.AUDIT_CHAIN_BROKEN, map("seq", b.seq()), map("kind", b.kind().name()));
        }
        auditCheck.counted((int) auditRows).close();

        // 2. 봉인 체인 + 3. 채번(연도별)
        ReportBuilder.CheckScope sealCheck = r.check("SEAL_CHAIN");
        SealChainWalker sealWalker = SealChainWalker.fromGenesis(sealCheckpoints);
        long head = chain.headSeq();
        List<SealChainReader.ChainRow> sealed = new ArrayList<>();
        for (long from = 1; from <= head; from += PAGE) {
            List<SealChainReader.ChainRow> page = chain.links(from, Math.min(head, from + PAGE - 1));
            for (SealChainReader.ChainRow row : page) {
                sealWalker.accept(new com.ga.disclosure.audit.chain.SealLink(row.chainSeq(), row.canonicalHash(), row.pdfHash(), row.chainHash()));
                sealed.add(row);
            }
        }
        Map<Long, DisclosureId> bySeq = new TreeMap<>();
        sealed.forEach(s -> bySeq.put(s.chainSeq(), s.disclosureId()));
        for (ChainBreak b : sealWalker.breaks()) {
            DisclosureId at = bySeq.get(b.seq());
            r.finding(FindingCode.SEAL_CHAIN_BROKEN, map("chainSeq", b.seq(), "disclosureId", at == null ? null : at.toString()),
                    map("kind", b.kind().name()));
        }
        if (sealWalker.lastSeq() != head) {
            r.finding(FindingCode.SEAL_CHAIN_BROKEN, map("chainSeq", head, "disclosureId", null), map("problem", "HEAD_NOT_REACHED"));
        }
        sealCheck.counted(sealed.size()).close();
        numbering(r, tenant, sealed);

        // 파기 감사: destroyed_at이 있으면 DISCLOSURE_DESTROYED 감사가 있고, 그 반대도(5 계획 §4 추가 코드)
        ReportBuilder.CheckScope destruction = r.check("DESTRUCTION_AUDIT");
        int destroyedCount = 0;
        for (SealChainReader.ChainRow row : sealed) {
            boolean destroyed = row.destroyedAtOrNull() != null;
            destroyedCount += destroyed ? 1 : 0;
            if (destroyed != destroyedAudited.contains(row.disclosureId().toString())) {
                r.finding(FindingCode.DESTRUCTION_UNAUDITED, map("disclosureId", row.disclosureId().toString()),
                        map("destroyed", destroyed, "audited", !destroyed));
            }
        }
        destruction.counted(destroyedCount).close();

        // 4. 객체
        ReportBuilder.CheckScope objects = r.check("OBJECTS");
        int objectCount = 0;
        for (SealChainReader.ChainRow row : sealed) {
            objectCount += objects(r, tenant, row);
        }
        objects.counted(objectCount).close();

        // 5. 앵커 · 6. 영수증 · 7. 미고정 기간
        ReportBuilder.CheckScope anchorCheck = r.check("ANCHORS").counted(all.size());
        ReportBuilder.CheckScope receiptCheck = r.check("RECEIPTS");
        int receipts = 0;
        LocalDate today = LocalDate.ofInstant(asOf, SEOUL);
        Optional<Integer> alertDays = alertDays(tenant, today);
        List<Map.Entry<StoredAnchor, AnchorReceipt>> stamped = new ArrayList<>();
        for (StoredAnchor a : all) {
            AnchorRecord rec = a.record();
            String problem = !rec.leafHash().equals(a.leafHash()) ? "LEAF"
                    : !sealWalker.headAt(rec.sealChainSeq()).map(h -> h.equals(rec.sealChainHead())).orElse(false) ? "SEAL_HEAD"
                    : !auditWalker.headAt(rec.auditSeq()).map(h -> h.equals(rec.auditHead())).orElse(false) ? "AUDIT_HEAD" : null;
            if (problem != null) {
                r.finding(FindingCode.ANCHOR_MISMATCH, map("anchorSeq", rec.anchorSeq(), "anchorDate", rec.anchorDate().toString()),
                        map("problem", problem));
            }
            Optional<AnchorReceipt> receipt = anchors.receipt(rec.anchorSeq());
            if (receipt.isPresent()) {
                stamped.add(Map.entry(a, receipt.get()));
            } else if (alertDays.isPresent() && rec.anchorDate().plusDays(alertDays.get()).isBefore(today)) {
                r.finding(FindingCode.ANCHOR_UNSTAMPED, map("anchorSeq", rec.anchorSeq(), "anchorDate", rec.anchorDate().toString()),
                        map("alertDays", (long) alertDays.get()));
            }
        }
        missingDays(r, all, today);
        anchorCheck.close();
        TimestampVerifier tokens = new TimestampVerifier(trust);
        for (Map.Entry<StoredAnchor, AnchorReceipt> e : stamped) {
            receipts++;
            AnchorRecord rec = e.getKey().record();
            AnchorReceipt rc = e.getValue();
            Map<String, Object> where = map("anchorSeq", rec.anchorSeq(), "batchId", rc.batchId().toString());
            if (!MerkleTree.verify(rec.canonical(), rc.leafIndex(), rc.merklePath(), rc.treeDepth(), rc.rootHash())) {
                r.finding(FindingCode.RECEIPT_PATH_INVALID, where, map("root", rc.rootHash()));
            }
            switch (tokens.verify(rc.tsaToken(), HexFormat.of().parseHex(rc.rootHash()))) {
                case TimestampVerification.Valid v -> {
                    if (!v.token().genTime().equals(rc.tsaGenTime()) || !v.token().serialHex().equals(rc.tsaSerial())) {
                        r.finding(FindingCode.TSA_INVALID, where, map("problem", "RECEIPT_FIELDS_DIFFER"));
                    }
                }
                case TimestampVerification.Invalid i -> r.finding(FindingCode.TSA_INVALID, where, map("reason", i.reason()));
                case TimestampVerification.Untrusted u -> r.finding(FindingCode.TSA_UNTRUSTED, where, map("reason", u.reason()));
            }
        }
        receiptCheck.counted(receipts).close();
        return new Read(r, new VerifyReport.Counts(sealed.size(), objectCount, auditRows, all.size(), receipts));
    }

    /**
     * 빠진 날(5 수용심사 R1, 6A 승인 Q10): 앵커는 내용 변화와 무관하게 매일 만들어지므로(앵커 감사 행이 머리를 바꾼다) 첫 앵커 날짜부터 어제(KST,
     * 검증 시계)까지 앵커가 없는 달력 날짜는 공백이다. 오늘은 아직 돌지 않았을 수 있어 빼고, 끝의 공백은 넣는다. 공백 구간마다 발견 1건.
     * 운영 신호다 — 증명 구간이 길어질 뿐 체인의 공백이 아니므로 {@code CHAIN_BROKEN}을 올리지 않는다(보고서는 불일치).
     */
    private static void missingDays(ReportBuilder r, List<StoredAnchor> all, LocalDate today) {
        if (all.isEmpty()) {
            return;
        }
        LocalDate yesterday = today.minusDays(1);
        for (int i = 0; i < all.size(); i++) {
            AnchorRecord rec = all.get(i).record();
            LocalDate from = rec.anchorDate().plusDays(1);
            LocalDate to = i + 1 < all.size() ? all.get(i + 1).record().anchorDate().minusDays(1) : yesterday;
            if (!from.isAfter(to)) {
                r.finding(FindingCode.ANCHOR_MISSING_DAY, map("afterAnchorSeq", rec.anchorSeq(), "fromDate", from.toString(), "toDate", to.toString()),
                        map("days", java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1));
            }
        }
    }

    private Optional<Integer> alertDays(TenantId tenant, LocalDate today) {
        try {
            return Optional.of(rules.resolve(tenant, today).unstampedAnchorAlertDays());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 연도별 채번: {@code {tenant}-{yyyy}-{nnnnnn}}의 순번이 1부터 빈틈·중복 없이 이어진다. */
    private static void numbering(ReportBuilder r, TenantId tenant, List<SealChainReader.ChainRow> sealed) {
        ReportBuilder.CheckScope check = r.check("NUMBERING").counted(sealed.size());
        Map<String, List<Long>> byYear = new TreeMap<>();
        String prefix = tenant.value() + "-";
        for (SealChainReader.ChainRow row : sealed) {
            String no = row.disclosureNo();
            if (!no.startsWith(prefix) || no.length() != prefix.length() + 11) {
                r.finding(FindingCode.NUMBERING_GAP, map("disclosureId", row.disclosureId().toString()), map("problem", "FORMAT"));
                continue;
            }
            byYear.computeIfAbsent(no.substring(prefix.length(), prefix.length() + 4), y -> new ArrayList<>())
                    .add(Long.parseLong(no.substring(prefix.length() + 5)));
        }
        byYear.forEach((year, numbers) -> {
            List<Long> sorted = numbers.stream().sorted().toList();
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i) != i + 1) {
                    r.finding(FindingCode.NUMBERING_GAP, map("year", year), map("expected", (long) (i + 1), "actual", sorted.get(i)));
                    break;
                }
            }
        });
        check.close();
    }

    /** 확인서 하나의 객체: 살아 있으면 있음·복호화·평문 해시, 파기됐으면 어느 버전·마커도 없음. 센 객체 수를 돌려준다. */
    private int objects(ReportBuilder r, TenantId tenant, SealChainReader.ChainRow row) {
        DisclosureId id = row.disclosureId();
        List<ArtifactRecord> artifacts = records.artifacts(id);
        List<SignatureEvidenceRecord> evidence = records.evidence(id);
        if (row.destroyedAtOrNull() != null) {
            for (String key : keys(artifacts, evidence)) {
                if (!storage.versionCount(key).isEmpty()) {
                    r.finding(FindingCode.OBJECT_NOT_DELETED, map("disclosureId", id.toString(), "storageKey", key), map());
                }
            }
            return artifacts.size() + evidence.size();
        }
        Optional<DocumentCryptoPort.StoredKey> key = records.liveKey(id);
        for (ArtifactRecord a : artifacts) {
            check(r, id, a.storageKey(), a.kind().name(), a.sha256().hex(),
                    () -> crypto.open(tenant, id, key.orElseThrow(), a.kind(), storage.get(a.storageKey())));
        }
        for (SignatureEvidenceRecord e : evidence) {
            check(r, id, e.storageKey(), e.kind().name(), e.sha256().hex(),
                    () -> crypto.openEvidence(tenant, id, key.orElseThrow(), e.signatureId(), e.kind(), storage.get(e.storageKey())));
        }
        return artifacts.size() + evidence.size();
    }

    private void check(ReportBuilder r, DisclosureId id, String storageKey, String kind, String expected, Supplier<byte[]> open) {
        Map<String, Object> where = map("disclosureId", id.toString(), "kind", kind, "storageKey", storageKey);
        if (!storage.exists(storageKey)) {
            r.finding(FindingCode.OBJECT_MISSING, where, map());
            return;
        }
        String actual;
        try {
            actual = Sha256.of(open.get());
        } catch (RuntimeException e) {
            r.finding(FindingCode.OBJECT_HASH_MISMATCH, where, map("problem", "UNREADABLE"));
            return;
        }
        if (!actual.equals(expected)) {
            r.finding(FindingCode.OBJECT_HASH_MISMATCH, where, map("expected", expected, "actual", actual));
        }
    }

    private static Set<String> keys(List<ArtifactRecord> artifacts, List<SignatureEvidenceRecord> evidence) {
        Set<String> keys = new LinkedHashSet<>();
        artifacts.forEach(a -> keys.add(a.storageKey()));
        evidence.forEach(e -> keys.add(e.storageKey()));
        return keys;
    }

    private void record(TenantId tenant, Actor actor, VerifyReport report) {
        Instant now = clock.instant();
        ObjectNode detail = JSON.createObjectNode().put("reportSha256", report.sha256()).put("result", report.matches() ? "MATCH" : "MISMATCH");
        ObjectNode codes = detail.putObject("findings");
        report.findings().forEach(f -> codes.put(f.code().name(), codes.path(f.code().name()).asInt(0) + 1));
        audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.VERIFY_RUN, "TENANT", tenant.value(), detail));
        Set<String> disclosures = new LinkedHashSet<>();
        Set<Map.Entry<String, String>> unattached = new LinkedHashSet<>();
        for (VerifyReport.Finding f : report.findings()) {
            if (OPERATIONAL.contains(f.code())) {
                continue;
            }
            Object at = f.where().get("disclosureId");
            if (at instanceof String s) {
                disclosures.add(s);
            } else if (f.where().get("seq") instanceof Long seq) {
                unattached.add(Map.entry("AUDIT_LOG", seq.toString()));
            } else if (f.where().get("anchorSeq") instanceof Long anchorSeq) {
                unattached.add(Map.entry("ANCHOR", anchorSeq.toString()));
            } else {
                unattached.add(Map.entry("TENANT", tenant.value()));
            }
        }
        for (String d : disclosures) {
            DisclosureFlagPort.RaisedFlag flag = flags.raise(DisclosureFlagPort.Type.CHAIN_BROKEN, "HIGH", DisclosureId.parse(d), "DISCLOSURE", d, now);
            flagAudit(actor, now, "DISCLOSURE", d, flag);
        }
        for (Map.Entry<String, String> target : unattached) {
            DisclosureFlagPort.RaisedFlag flag = flags.raiseUnattached(DisclosureFlagPort.Type.CHAIN_BROKEN, "HIGH", target.getKey(), target.getValue(), now);
            flagAudit(actor, now, target.getKey(), target.getValue(), flag);
        }
    }

    private void flagAudit(Actor actor, Instant now, String targetKind, String targetId, DisclosureFlagPort.RaisedFlag flag) {
        audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.FLAG_RAISE, targetKind, targetId,
                JSON.createObjectNode().put("flagId", flag.flagId().toString()).put("type", DisclosureFlagPort.Type.CHAIN_BROKEN.name())
                        .put("created", flag.created())));
    }
}
