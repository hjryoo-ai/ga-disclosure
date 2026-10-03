package com.ga.disclosure.workflow.anchor;

import com.ga.disclosure.audit.AuditChain;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.audit.anchor.AnchorRecord;
import com.ga.disclosure.audit.tsa.NonceSource;
import com.ga.disclosure.audit.tsa.TimestampClient;
import com.ga.disclosure.audit.tsa.stub.LocalStubTsa;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.disclosure.rules.version.RuleVersionPort;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.ConcurrentWriteConflict;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앵커 배치의 실패 분기(DB 없이 — 인메모리 포트): 테넌트끼리 트리 깊이가 다르면 그 날짜는 영수증 0(fail-fast), 잎이 2^깊이를 넘으면 TREE_FULL, 룰을
 * 해석하지 못한 테넌트는 RULE_UNRESOLVED로 빠지고 나머지는 고정된다, 충돌이 계속되면 상한까지만 재시도한다.
 */
class AnchorJobTest {

    static final LocalDate DAY = LocalDate.parse("2026-09-23");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T01:00:00Z"), ZoneOffset.UTC);
    static final Actor SYSTEM = new Actor("system:anchor", "SYSTEM");
    static final TenantId A = TenantId.of("ANC_A");
    static final TenantId B = TenantId.of("ANC_B");
    static final TenantId C = TenantId.of("ANC_C");

    final InMemoryAnchors anchors = new InMemoryAnchors();
    final InMemoryAudit audit = new InMemoryAudit();
    final LocalStubTsa tsa = LocalStubTsa.ephemeral(CLOCK);

    AnchorJob job(Map<TenantId, Integer> depths, WorkflowTransactions tx) {
        RuleVersionPort rules = new RuleVersionPort() {
            @Override
            public List<RuleVersion> findActive(TenantId tenant, RuleScope scope, LocalDate asOf) {
                Integer depth = depths.get(tenant);
                return depth == null || scope != RuleScope.GLOBAL ? List.of() : List.of(withDepth(depth));
            }

            @Override
            public Optional<RuleVersion> findById(TenantId tenant, RuleVersionId id) {
                return Optional.empty();
            }
        };
        return new AnchorJob(anchors, audit, tx, new RuleResolver(rules), new TimestampClient(tsa, NonceSource.secure(), tsa.trustAnchors()), CLOCK);
    }

    static RuleVersion withDepth(int depth) {
        ObjectNode body = (ObjectNode) Bundles.rule(Bundles.DISC_2026_07).body().deepCopy();
        ((ObjectNode) body.get("anchoring")).put("treeDepth", depth);
        return Bundles.global("DISC-ANCHOR-TEST", LocalDate.parse("2026-07-01"), null, RuleStatus.ACTIVE, body);
    }

    @Test
    void tenantsThatDisagreeOnTheDepthGetNoReceiptThatDay() {
        AnchorJob.Report report = job(Map.of(A, 16, B, 12), new Bound()).run(List.of(A, B), DAY, SYSTEM);

        assertThat(report.created()).containsExactly(A, B);
        assertThat(report.receipts()).isZero();
        assertThat(report.batches()).isEmpty();
        assertThat(report.failures()).containsExactly(new AnchorJob.Failure("B", null, DAY, "TREE_DEPTH_DISAGREES"));
    }

    @Test
    void moreLeavesThanTheTreeHoldsIsReportedNotTruncated() {
        AnchorJob.Report report = job(Map.of(A, 1, B, 1, C, 1), new Bound()).run(List.of(A, B, C), DAY, SYSTEM);

        assertThat(report.receipts()).isZero();
        assertThat(report.failures()).containsExactly(new AnchorJob.Failure("B", null, DAY, "TREE_FULL"));
    }

    @Test
    void aTenantWithoutARuleIsLeftOutAndTheOthersAreStamped() {
        AnchorJob.Report report = job(Map.of(A, 16), new Bound()).run(List.of(A, B), DAY, SYSTEM);

        assertThat(report.failures()).containsExactly(new AnchorJob.Failure("B", B, DAY, "RULE_UNRESOLVED"));
        assertThat(report.receipts()).isEqualTo(1);
        assertThat(report.batches()).singleElement().satisfies(b -> assertThat(b.leaves()).isEqualTo(1));
    }

    /** 5 수용심사 R1: 오늘(KST)이 아닌 날짜 — 미래도 소급도 — 는 앵커도 영수증도 만들지 않는다. 오늘은 된다. */
    @Test
    void aDateOtherThanTodayIsRefusedForEveryTenantAndTodayIsAllowed() {
        for (LocalDate other : List.of(DAY.plusDays(1), DAY.minusDays(1))) {
            AnchorJob.Report refused = job(Map.of(A, 16, B, 16), new Bound()).run(List.of(A, B), other, SYSTEM);

            assertThat(refused.failures()).as(other.toString()).containsExactly(new AnchorJob.Failure("A", A, other, "DATE_NOT_TODAY"),
                    new AnchorJob.Failure("A", B, other, "DATE_NOT_TODAY"));
            assertThat(refused.created()).isEmpty();
            assertThat(refused.receipts()).isZero();
        }
        assertThat(anchors.rows).as("거부된 날짜는 아무것도 남기지 않는다").isEmpty();
        assertThat(job(Map.of(A, 16), new Bound()).run(List.of(A), DAY, SYSTEM).created()).containsExactly(A);
    }

    @Test
    void conflictsAreRetriedOnlyUpToTheLimit() {
        Bound alwaysConflicting = new Bound() {
            @Override
            public <T> T inTenantRepeatableRead(TenantId tenant, Supplier<T> work) {
                throw new ConcurrentWriteConflict("23505", null);
            }
        };

        AnchorJob.Report report = job(Map.of(A, 16), alwaysConflicting).run(List.of(A), DAY, SYSTEM);

        assertThat(report.retries()).containsEntry(A, AnchorJob.MAX_ATTEMPTS);
        assertThat(report.failures()).containsExactly(new AnchorJob.Failure("A", A, DAY, "RETRIES_EXHAUSTED"));
        assertThat(report.created()).isEmpty();
    }

    /** 테넌트를 바인딩하고 그대로 실행한다(트랜잭션 없음). */
    static class Bound implements WorkflowTransactions {
        @Override
        public <T> T inTenant(TenantId tenant, Supplier<T> work) {
            AtomicReference<T> out = new AtomicReference<>();
            TenantContext.runWith(tenant, () -> out.set(work.get()));
            return out.get();
        }

        @Override
        public <T> T inTenant(TenantId tenant, Duration timeout, Supplier<T> work) {
            return inTenant(tenant, work);
        }

        @Override
        public <T> T inTenantRepeatableRead(TenantId tenant, Supplier<T> work) {
            return inTenant(tenant, work);
        }
    }

    static final class InMemoryAudit implements AuditPort {
        final Map<TenantId, List<AuditRecord>> rows = new HashMap<>();

        @Override
        public AuditRecord append(AuditEntry entry) {
            List<AuditRecord> log = rows.computeIfAbsent(TenantContext.current(), t -> new ArrayList<>());
            AuditRecord next = AuditChain.next(TenantContext.current(), log.isEmpty() ? null : log.getLast(), entry);
            log.add(next);
            return next;
        }

        @Override
        public List<AuditRecord> readAll() {
            return List.copyOf(rows.getOrDefault(TenantContext.current(), List.of()));
        }

        @Override
        public List<AuditRecord> readAfter(long afterSeq, int limit) {
            return readAll().stream().filter(r -> r.seq() > afterSeq).limit(limit).toList();
        }

        @Override
        public List<AuditRecord> readTarget(String targetKind, String targetId) {
            return readAll().stream().filter(r -> r.entry().targetKind().equals(targetKind) && r.entry().targetId().equals(targetId)).toList();
        }
    }

    final class InMemoryAnchors implements AnchorStore {
        final Map<TenantId, List<StoredAnchor>> rows = new HashMap<>();
        final Map<TenantId, Map<Long, AnchorReceipt>> receipts = new HashMap<>();

        private List<StoredAnchor> mine() {
            return rows.computeIfAbsent(TenantContext.current(), t -> new ArrayList<>());
        }

        @Override
        public Optional<StoredAnchor> onDate(LocalDate anchorDate) {
            return mine().stream().filter(a -> a.record().anchorDate().equals(anchorDate)).findFirst();
        }

        @Override
        public Optional<StoredAnchor> latest() {
            return mine().isEmpty() ? Optional.empty() : Optional.of(mine().getLast());
        }

        @Override
        public ChainHeads heads() {
            List<AuditRecord> log = audit.readAll();
            return log.isEmpty() ? new ChainHeads(0, AuditChain.GENESIS, 0, AuditChain.GENESIS)
                    : new ChainHeads(0, AuditChain.GENESIS, log.getLast().seq(), log.getLast().entryHash());
        }

        @Override
        public void insert(AnchorRecord record, Instant createdAt) {
            mine().add(new StoredAnchor(record, record.leafHash(), createdAt));
        }

        @Override
        public List<StoredAnchor> all() {
            return List.copyOf(mine());
        }

        @Override
        public List<StoredAnchor> unstamped() {
            Map<Long, AnchorReceipt> done = receipts.getOrDefault(TenantContext.current(), Map.of());
            return mine().stream().filter(a -> !done.containsKey(a.record().anchorSeq())).toList();
        }

        @Override
        public void insertReceipt(AnchorReceipt receipt) {
            receipts.computeIfAbsent(TenantContext.current(), t -> new HashMap<>()).put(receipt.anchorSeq(), receipt);
        }

        @Override
        public Optional<AnchorReceipt> receipt(long anchorSeq) {
            return Optional.ofNullable(receipts.getOrDefault(TenantContext.current(), Map.of()).get(anchorSeq));
        }
    }
}
