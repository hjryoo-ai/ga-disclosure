package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.audit.anchor.AnchorRecord;
import com.ga.disclosure.audit.anchor.MerkleTree;
import com.ga.disclosure.audit.tsa.NonceSource;
import com.ga.disclosure.audit.tsa.TimestampClient;
import com.ga.disclosure.audit.tsa.TimestampFailure;
import com.ga.disclosure.audit.tsa.TimestampVerification;
import com.ga.disclosure.audit.tsa.TimestampVerifier;
import com.ga.disclosure.audit.tsa.stub.LocalStubTsa;
import com.ga.disclosure.infra.persistence.AnchorRepository;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.anchor.AnchorJob;
import com.ga.disclosure.workflow.anchor.AnchorReceipt;
import com.ga.disclosure.workflow.anchor.AnchorStore;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1·G4(5 계획 §8.1): 테넌트·날짜당 앵커 1행(재실행 NOOP·새 토큰 0), 잎 = JCS 해시(앱 = DB — GD110이 다시 계산해 받아들였다), 두 머리는 한
 * 스냅샷이고 봉인이 병행해도 그 seq의 실제 값이다, {@code audit_seq} = 자기 {@code ANCHOR_CREATED} 직전, UTC 자정을 넘는 KST 하루, 루트 하나에
 * 영수증 테넌트마다(경로로 루트 재계산 — GD111, 토큰은 스텁 신뢰 앵커로 VALID), TSA 불가 → 앵커만 남고 다음 실행이 둘째 배치.
 */
class AnchorJobIT {

    static final Actor SYSTEM = new Actor("system:anchor", "SYSTEM");
    static final LocalDate DAY = LocalDate.parse("2026-09-23");

    final SealSetup a = new SealSetup();
    final WorkflowSetup b = new WorkflowSetup();
    final AnchorRepository anchors = new AnchorRepository(a.w.gateway);
    final LocalStubTsa tsa = LocalStubTsa.ephemeral(a.w.clock);

    @AfterEach
    void close() {
        a.close();
        b.close();
    }

    AnchorJob job(Clock clock, com.ga.disclosure.audit.tsa.TimestampAuthorityPort port, AnchorStore store) {
        return new AnchorJob(store, a.w.audit, a.w.tx, new RuleResolver(a.w.rules), new TimestampClient(port, NonceSource.secure(), tsa.trustAnchors()),
                clock);
    }

    AnchorJob job() {
        return job(a.w.clock, tsa, anchors);
    }

    List<TenantId> both() {
        return List.of(a.w.tenant, b.tenant);
    }

    AnchorStore.StoredAnchor anchorOf(TenantId tenant) {
        return a.w.tx.inTenant(tenant, () -> anchors.onDate(DAY)).orElseThrow();
    }

    AnchorReceipt receiptOf(TenantId tenant, long seq) {
        return a.w.tx.inTenant(tenant, () -> anchors.receipt(seq)).orElseThrow();
    }

    List<AuditRecord> auditOf(TenantId tenant) {
        return a.w.tx.inTenant(tenant, a.w.audit::readAll);
    }

    @Test
    void everyTenantGetsOneAnchorAndOneRootIsStampedForAll() {
        a.sealReasoned();
        AnchorJob.Report report = job().run(both(), DAY, SYSTEM);

        assertThat(report.created()).containsExactly(a.w.tenant, b.tenant);
        assertThat(report.failures()).isEmpty();
        assertThat(report.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.leaves()).isEqualTo(2);
            assertThat(batch.second()).isFalse();
            assertThat(batch.depth()).isEqualTo(16);
        });
        assertThat(report.receipts()).isEqualTo(2);

        AnchorStore.StoredAnchor anchorA = anchorOf(a.w.tenant);
        AnchorRecord ra = anchorA.record();
        assertThat(anchorA.leafHash()).isEqualTo(ra.leafHash());
        assertThat(ra.sealChainSeq()).isEqualTo(1);
        assertThat(ra.sealChainHead()).isEqualTo(a.text("SELECT chain_hash FROM disclosure WHERE tenant_id = ? AND chain_seq = 1", a.w.tenant.value()));
        assertThat(anchorOf(b.tenant).record().sealChainSeq()).isZero();

        // audit_seq = 자기 ANCHOR_CREATED 행 직전, audit_head = 그 seq 행의 entry_hash
        List<AuditRecord> logA = auditOf(a.w.tenant);
        AuditRecord created = logA.stream().filter(r -> r.entry().action() == AuditAction.ANCHOR_CREATED).findFirst().orElseThrow();
        assertThat(created.seq()).isEqualTo(ra.auditSeq() + 1);
        assertThat(logA.get((int) ra.auditSeq() - 1).entryHash()).isEqualTo(ra.auditHead());
        assertThat(logA).anyMatch(r -> r.entry().action() == AuditAction.ANCHOR_RECEIPT_STORED);

        AnchorReceipt receiptA = receiptOf(a.w.tenant, ra.anchorSeq());
        AnchorReceipt receiptB = receiptOf(b.tenant, anchorOf(b.tenant).record().anchorSeq());
        assertThat(receiptA.batchId()).isEqualTo(receiptB.batchId());
        assertThat(receiptA.rootHash()).isEqualTo(receiptB.rootHash()).isEqualTo(report.batches().getFirst().root());
        assertThat(receiptA.merklePath()).hasSize(16);
        assertThat(MerkleTree.verify(ra.canonical(), receiptA.leafIndex(), receiptA.merklePath(), receiptA.treeDepth(), receiptA.rootHash())).isTrue();
        assertThat(new TimestampVerifier(tsa.trustAnchors()).verify(receiptA.tsaToken(), HexFormat.of().parseHex(receiptA.rootHash())))
                .isInstanceOf(TimestampVerification.Valid.class);
    }

    @Test
    void aSecondRunOnTheSameDayChangesNothing() {
        job().run(both(), DAY, SYSTEM);
        long auditBefore = auditOf(a.w.tenant).size();

        AnchorJob.Report again = job().run(both(), DAY, SYSTEM);

        assertThat(again.created()).isEmpty();
        assertThat(again.unchanged()).containsExactly(a.w.tenant, b.tenant);
        assertThat(again.batches()).isEmpty();
        assertThat(again.receipts()).isZero();
        assertThat(auditOf(a.w.tenant)).hasSize((int) auditBefore);
        assertThat(a.count("SELECT count(*) FROM anchor WHERE tenant_id = ?", a.w.tenant.value())).isEqualTo(1);
    }

    @Test
    void aTsaOutageLeavesAnchorsAndTheNextRunStampsThemInASecondBatch() {
        AnchorJob.Report down = job(a.w.clock, request -> {
            throw new TimestampFailure(TimestampFailure.Kind.UNAVAILABLE, "TRANSPORT");
        }, anchors).run(both(), DAY, SYSTEM);

        assertThat(down.created()).hasSize(2);
        assertThat(down.receipts()).isZero();
        assertThat(down.failures()).singleElement().satisfies(f -> assertThat(f.code()).isEqualTo("TSA_UNAVAILABLE:TRANSPORT"));
        assertThat(a.count("SELECT count(*) FROM anchor_receipt WHERE tenant_id = ?", a.w.tenant.value())).isZero();

        AnchorJob.Report next = job().run(both(), DAY, SYSTEM);

        assertThat(next.created()).isEmpty();
        assertThat(next.receipts()).isEqualTo(2);
        assertThat(next.secondBatches()).isEqualTo(1);
    }

    @Test
    void headsComeFromOneSnapshotEvenWhenASealCommitsInBetween() throws Exception {
        a.sealReasoned();
        AtomicBoolean injected = new AtomicBoolean();
        AnchorStore racing = new DelegatingAnchorStore(anchors) {
            @Override
            public ChainHeads heads() {
                ChainHeads heads = super.heads();
                if (injected.compareAndSet(false, true)) {
                    CompletableFuture.runAsync(a::sealReasoned).join();     // 스냅샷 뒤에 다른 트랜잭션이 봉인·감사를 커밋한다
                }
                return heads;
            }
        };

        AnchorJob.Report report = job(a.w.clock, tsa, racing).run(List.of(a.w.tenant), DAY, SYSTEM);

        assertThat(report.retries()).containsEntry(a.w.tenant, 1);
        AnchorRecord r = anchorOf(a.w.tenant).record();
        assertThat(r.sealChainSeq()).as("재시도는 새 스냅샷에서 두 번째 봉인까지 본다").isEqualTo(2);
        assertThat(r.sealChainHead()).isEqualTo(a.text("SELECT chain_hash FROM disclosure WHERE tenant_id = ? AND chain_seq = 2", a.w.tenant.value()));
        List<AuditRecord> log = auditOf(a.w.tenant);
        assertThat(log.get((int) r.auditSeq() - 1).entryHash()).isEqualTo(r.auditHead());
        assertThat(log.get((int) r.auditSeq()).entry().action()).isEqualTo(AuditAction.ANCHOR_CREATED);
    }

    @Test
    void theKstDayIsTheAnchorDateEvenAfterUtcMidnight() {
        Clock late = Clock.fixed(Instant.parse("2026-09-23T15:30:00Z"), ZoneOffset.UTC);   // KST 2026-09-24 00:30
        AnchorJob kst = new AnchorJob(anchors, a.w.audit, a.w.tx, new RuleResolver(a.w.rules),
                new TimestampClient(tsa, NonceSource.secure(), tsa.trustAnchors()), late);

        kst.run(List.of(a.w.tenant), SYSTEM);

        assertThat(a.text("SELECT anchor_date::text FROM anchor WHERE tenant_id = ?", a.w.tenant.value())).isEqualTo("2026-09-24");
    }

    @Test
    void anEarlierDayIsNotAnchoredAfterALaterOne() {
        job().run(List.of(a.w.tenant), DAY, SYSTEM);
        AnchorJob.Report earlier = job().run(List.of(a.w.tenant), DAY.minusDays(1), SYSTEM);

        assertThat(earlier.created()).isEmpty();
        assertThat(earlier.failures()).singleElement().satisfies(f -> assertThat(f.code()).isEqualTo("DATE_NOT_AFTER_LATEST"));
    }

    /** 위임 기반: 테스트가 특정 지점에 끼어든다. */
    static class DelegatingAnchorStore implements AnchorStore {
        private final AnchorStore delegate;

        DelegatingAnchorStore(AnchorStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<StoredAnchor> onDate(LocalDate anchorDate) {
            return delegate.onDate(anchorDate);
        }

        @Override
        public java.util.Optional<StoredAnchor> latest() {
            return delegate.latest();
        }

        @Override
        public ChainHeads heads() {
            return delegate.heads();
        }

        @Override
        public void insert(AnchorRecord record, Instant createdAt) {
            delegate.insert(record, createdAt);
        }

        @Override
        public List<StoredAnchor> all() {
            return delegate.all();
        }

        @Override
        public List<StoredAnchor> unstamped() {
            return delegate.unstamped();
        }

        @Override
        public void insertReceipt(AnchorReceipt receipt) {
            delegate.insertReceipt(receipt);
        }

        @Override
        public java.util.Optional<AnchorReceipt> receipt(long anchorSeq) {
            return delegate.receipt(anchorSeq);
        }
    }
}
