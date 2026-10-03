package com.ga.disclosure.workflow.anchor;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.anchor.AnchorRecord;
import com.ga.disclosure.audit.anchor.MerkleTree;
import com.ga.disclosure.audit.tsa.StampResponse;
import com.ga.disclosure.audit.tsa.TimestampClient;
import com.ga.disclosure.audit.tsa.TimestampFailure;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.ConcurrentWriteConflict;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.anchor.AnchorStore.ChainHeads;
import com.ga.disclosure.workflow.anchor.AnchorStore.StoredAnchor;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 일일 앵커(5 계획 §8.1, 설계서 §6.7). 스케줄 등록은 Phase 6.
 * <ol>
 *   <li><b>A단계</b> — 테넌트마다 REPEATABLE READ 트랜잭션 하나(승인 Q12): 그 날짜 앵커가 있으면 NOOP. 없으면 한 스냅샷에서 봉인 체인 머리와 감사 머리를
 *       읽어 앵커를 쓰고(DB가 잎·머리를 다시 계산, GD110) 감사 {@code ANCHOR_CREATED}를 남긴다 — 그 감사 행의 seq가 앵커 {@code audit_seq + 1}이다.
 *       스냅샷 뒤 다른 커밋과 부딪히면(감사 seq 23505·직렬화 40001) 그 테넌트만 처음부터 다시 한다(상한 {@value #MAX_ATTEMPTS}회, 보고서
 *       {@code retries}).</li>
 *   <li><b>B단계</b> — 영수증 없는 앵커 전부(지난 날짜 포함)를 날짜별로 잎으로 묶어 루트 하나에 TSA 토큰을 받고, 테넌트 트랜잭션마다 영수증과 감사
 *       {@code ANCHOR_RECEIPT_STORED}를 쓴다. 트리 깊이는 각 테넌트가 그 날짜에 해석한 GLOBAL 룰 {@code anchoring.treeDepth}이며 테넌트끼리 다르면
 *       그 날짜 배치는 실패한다(fail-fast). TSA 실패는 봉인·서명·완료를 막지 않는다 — 앵커는 남고 영수증만 비어 다음 실행이 둘째 배치로 잇는다.</li>
 * </ol>
 * 실패는 보고서에 남고 다른 테넌트·날짜는 계속한다.
 */
public final class AnchorJob {

    public static final int MAX_ATTEMPTS = 5;
    public static final String TARGET = "ANCHOR";
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 배치 하나: 같은 날짜의 잎들, 루트·토큰 시각. {@code second}는 이 실행 전에 만들어진(지난 실행에서 고정되지 못한) 앵커를 담았는가. */
    public record Batch(LocalDate anchorDate, UUID batchId, String root, int depth, int leaves, boolean second, Instant genTime) {
    }

    /** 실패 하나: 단계(A·B)·테넌트(배치 전체면 null)·날짜·코드(메시지 없음). */
    public record Failure(String stage, TenantId tenant, LocalDate anchorDate, String code) {
    }

    public record Report(LocalDate date, List<TenantId> created, List<TenantId> unchanged, Map<TenantId, Integer> retries, List<Batch> batches,
                         int receipts, List<Failure> failures) {
        public Report {
            created = List.copyOf(created);
            unchanged = List.copyOf(unchanged);
            retries = Map.copyOf(retries);
            batches = List.copyOf(batches);
            failures = List.copyOf(failures);
        }

        public long secondBatches() {
            return batches.stream().filter(Batch::second).count();
        }
    }

    private final AnchorStore store;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final RuleResolver rules;
    private final TimestampClient tsa;
    private final Clock clock;
    private final Supplier<UUID> batchIds;

    public AnchorJob(AnchorStore store, AuditPort audit, WorkflowTransactions transactions, RuleResolver rules, TimestampClient tsa, Clock clock,
                     Supplier<UUID> batchIds) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.tsa = Objects.requireNonNull(tsa, "tsa");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchIds = Objects.requireNonNull(batchIds, "batchIds");
    }

    public AnchorJob(AnchorStore store, AuditPort audit, WorkflowTransactions transactions, RuleResolver rules, TimestampClient tsa, Clock clock) {
        this(store, audit, transactions, rules, tsa, clock, UUID::randomUUID);
    }

    /** 오늘(KST) 날짜로 실행한다. */
    public Report run(List<TenantId> tenants, Actor actor) {
        return run(tenants, LocalDate.ofInstant(clock.instant(), SEOUL), actor);
    }

    public Report run(List<TenantId> tenants, LocalDate date, Actor actor) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(actor, "actor");
        List<TenantId> created = new ArrayList<>();
        List<TenantId> unchanged = new ArrayList<>();
        Map<TenantId, Integer> retries = new LinkedHashMap<>();
        List<Failure> failures = new ArrayList<>();
        Set<String> createdLeaves = new HashSet<>();
        for (TenantId tenant : List.copyOf(tenants)) {
            int attempt = 0;
            while (true) {
                attempt++;
                try {
                    Outcome outcome = transactions.inTenantRepeatableRead(tenant, () -> createOne(tenant, date, actor));
                    switch (outcome) {
                        case Outcome.Created c -> {
                            created.add(tenant);
                            createdLeaves.add(c.leafHash());
                        }
                        case Outcome.Unchanged u -> unchanged.add(tenant);
                        case Outcome.Refused r -> failures.add(new Failure("A", tenant, date, r.code()));
                    }
                    break;
                } catch (ConcurrentWriteConflict e) {
                    retries.merge(tenant, 1, Integer::sum);
                    if (attempt >= MAX_ATTEMPTS) {
                        failures.add(new Failure("A", tenant, date, "RETRIES_EXHAUSTED"));
                        break;
                    }
                }
            }
        }
        List<Batch> batches = new ArrayList<>();
        int receipts = stampUnstamped(List.copyOf(tenants), actor, createdLeaves, batches, failures);
        return new Report(date, created, unchanged, retries, batches, receipts, failures);
    }

    private sealed interface Outcome {
        record Created(String leafHash) implements Outcome {
        }

        record Unchanged() implements Outcome {
        }

        record Refused(String code) implements Outcome {
        }
    }

    private Outcome createOne(TenantId tenant, LocalDate date, Actor actor) {
        if (store.onDate(date).isPresent()) {
            return new Outcome.Unchanged();
        }
        ChainHeads heads = store.heads();
        var latest = store.latest();
        if (latest.isPresent() && !latest.get().record().anchorDate().isBefore(date)) {
            return new Outcome.Refused("DATE_NOT_AFTER_LATEST");             // GD110도 거부한다 — 지난 날짜를 뒤늦게 고정하지 않는다
        }
        long seq = latest.map(a -> a.record().anchorSeq() + 1).orElse(1L);
        AnchorRecord record = new AnchorRecord(tenant, seq, date, heads.sealChainSeq(), heads.sealChainHead(), heads.auditSeq(), heads.auditHead());
        Instant now = clock.instant();
        store.insert(record, now);
        String leaf = record.leafHash();
        audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.ANCHOR_CREATED, TARGET, date.toString(),
                JSON.createObjectNode().put("anchorSeq", seq).put("anchorDate", date.toString())
                        .put("sealChainSeq", heads.sealChainSeq()).put("sealChainHead", heads.sealChainHead())
                        .put("auditSeq", heads.auditSeq()).put("auditHead", heads.auditHead()).put("leafHash", leaf)));
        return new Outcome.Created(leaf);
    }

    private record Leaf(TenantId tenant, StoredAnchor anchor, int depth) {
    }

    private int stampUnstamped(List<TenantId> tenants, Actor actor, Set<String> createdLeaves, List<Batch> batches, List<Failure> failures) {
        Map<LocalDate, List<Leaf>> byDate = new TreeMap<>();
        for (TenantId tenant : tenants) {
            List<StoredAnchor> open = transactions.inTenant(tenant, store::unstamped);
            for (StoredAnchor a : open) {
                LocalDate day = a.record().anchorDate();
                try {
                    int depth = transactions.inTenant(tenant, () -> rules.resolve(tenant, day).anchoringTreeDepth());
                    byDate.computeIfAbsent(day, d -> new ArrayList<>()).add(new Leaf(tenant, a, depth));
                } catch (RuntimeException e) {
                    failures.add(new Failure("B", tenant, day, "RULE_UNRESOLVED"));
                }
            }
        }
        int receipts = 0;
        for (Map.Entry<LocalDate, List<Leaf>> day : byDate.entrySet()) {
            receipts += stampDay(day.getKey(), day.getValue(), actor, createdLeaves, batches, failures);
        }
        return receipts;
    }

    private int stampDay(LocalDate day, List<Leaf> leaves, Actor actor, Set<String> createdLeaves, List<Batch> batches, List<Failure> failures) {
        Set<Integer> depths = new HashSet<>();
        leaves.forEach(l -> depths.add(l.depth()));
        if (depths.size() != 1) {
            failures.add(new Failure("B", null, day, "TREE_DEPTH_DISAGREES"));
            return 0;
        }
        int depth = depths.iterator().next();
        MerkleTree.Tree tree;
        try {
            tree = MerkleTree.build(leaves.stream().map(l -> l.anchor().leafHash()).toList(), depth);
        } catch (MerkleTree.TreeFullException e) {
            failures.add(new Failure("B", null, day, "TREE_FULL"));
            return 0;
        }
        StampResponse stamp;
        try {
            stamp = tsa.stamp(HexFormat.of().parseHex(tree.root()));
        } catch (TimestampFailure e) {
            failures.add(new Failure("B", null, day, "TSA_" + e.kind() + ":" + e.reason()));
            return 0;
        }
        UUID batchId = batchIds.get();
        boolean second = leaves.stream().anyMatch(l -> !createdLeaves.contains(l.anchor().leafHash()));
        batches.add(new Batch(day, batchId, tree.root(), depth, leaves.size(), second, stamp.token().genTime()));
        int written = 0;
        for (Leaf leaf : leaves) {
            MerkleTree.Proof proof = tree.proof(leaf.anchor().leafHash());
            Instant now = clock.instant();
            AnchorReceipt receipt = new AnchorReceipt(leaf.anchor().record().anchorSeq(), batchId, tree.root(), depth, proof.leafIndex(),
                    proof.siblings(), stamp.tokenDer(), stamp.token().genTime(), stamp.token().policyOid(), stamp.token().serialHex(), now);
            try {
                transactions.inTenant(leaf.tenant(), () -> {
                    store.insertReceipt(receipt);
                    ObjectNode detail = JSON.createObjectNode().put("anchorSeq", receipt.anchorSeq()).put("anchorDate", day.toString())
                            .put("batchId", batchId.toString()).put("root", tree.root()).put("treeDepth", depth).put("leafIndex", proof.leafIndex())
                            .put("genTime", stamp.token().genTime().toString()).put("tsaSerial", stamp.token().serialHex());
                    audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.ANCHOR_RECEIPT_STORED, TARGET, day.toString(), detail));
                    return null;
                });
                written++;
            } catch (RuntimeException e) {
                failures.add(new Failure("B", leaf.tenant(), day, "RECEIPT_NOT_WRITTEN"));
            }
        }
        return written;
    }
}
