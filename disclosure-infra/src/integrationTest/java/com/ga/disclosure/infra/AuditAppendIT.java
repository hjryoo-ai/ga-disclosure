package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditChain;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.infra.persistence.AuditLogRepository;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantSessionBinder;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 C11: 감사 행의 해시 연쇄가 연속(DB에서 다시 읽어 전 행 재계산 일치), 동시 append 50건에서 seq 중복·갭 0,
 * 테넌트별 독립 체인, 업무 롤백 시 감사 행도 사라짐, 트리거를 우회한 변조는 재계산이 잡는다.
 */
class AuditAppendIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuditLogRepository audit = new AuditLogRepository(new TenantJdbcGateway(DB.appDataSource()));
    private final TransactionTemplate tx = new TransactionTemplate(new TenantSessionBinder(DB.appDataSource()));

    private static TenantId freshTenant() {
        String t = SeedData.uniqueTenant("AUD");
        DB.seed(t, c -> SeedData.tenant(c, t));
        return TenantId.of(t);
    }

    private static AuditEntry entry(int i) {
        return new AuditEntry(Instant.parse("2026-09-29T00:00:00Z").plusNanos(1_234_567L * i), "ops-" + i, "OPERATOR",
                AuditAction.RULE_DISTRIBUTE, "RULE_VERSION", "DISC-" + i, JSON.readTree("{\"outcome\":\"INSERTED\",\"n\":" + i + "}"));
    }

    private <T> T inTenant(TenantId tenant, Supplier<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        TenantContext.runWith(tenant, () -> result.set(tx.execute(s -> work.get())));
        return result.get();
    }

    private AuditRecord append(TenantId tenant, AuditEntry e) {
        return inTenant(tenant, () -> audit.append(e));
    }

    private List<AuditRecord> readAll(TenantId tenant) {
        return inTenant(tenant, audit::readAll);
    }

    @Test
    void sequentialAppendsFormAChainThatRecomputesFromTheDatabase() {
        TenantId tenant = freshTenant();
        List<AuditRecord> written = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            written.add(append(tenant, entry(i)));
        }
        List<AuditRecord> stored = readAll(tenant);
        assertThat(stored).extracting(AuditRecord::seq).containsExactlyElementsOf(LongStream.rangeClosed(1, 20).boxed().toList());
        assertThat(stored.getFirst().prevHash()).isEqualTo(AuditChain.GENESIS);
        assertThat(stored).extracting(AuditRecord::entryHash).containsExactlyElementsOf(written.stream().map(AuditRecord::entryHash).toList());
        assertThat(AuditChain.breaks(stored)).isEmpty();
    }

    @Test
    void fiftyConcurrentAppendsHaveNoDuplicateOrMissingSeq() throws Exception {
        TenantId tenant = freshTenant();
        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AuditRecord>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            futures.add(pool.submit(() -> {
                start.await();
                return append(tenant, entry(k));
            }));
        }
        start.countDown();
        List<Long> returned = new ArrayList<>();
        for (Future<AuditRecord> f : futures) {
            returned.add(f.get().seq());
        }
        pool.shutdown();
        assertThat(returned).doesNotHaveDuplicates().hasSize(n);
        List<AuditRecord> stored = readAll(tenant);
        assertThat(stored).extracting(AuditRecord::seq).containsExactlyElementsOf(LongStream.rangeClosed(1, n).boxed().toList());
        assertThat(AuditChain.breaks(stored)).isEmpty();
    }

    @Test
    void eachTenantHasItsOwnChain() {
        TenantId a = freshTenant();
        TenantId b = freshTenant();
        append(a, entry(1));
        append(b, entry(1));
        append(a, entry(2));
        assertThat(readAll(a)).extracting(AuditRecord::seq).containsExactly(1L, 2L);
        assertThat(readAll(b)).extracting(AuditRecord::seq).containsExactly(1L);
        assertThat(readAll(b).getFirst().prevHash()).isEqualTo(AuditChain.GENESIS);
    }

    @Test
    void auditRowIsPartOfTheBusinessTransaction() {
        TenantId tenant = freshTenant();
        append(tenant, entry(1));
        try {
            TenantContext.runWith(tenant, () -> tx.executeWithoutResult(s -> {
                audit.append(entry(2));
                throw new IllegalStateException("business failure");
            }));
        } catch (IllegalStateException expected) {
            // 업무 실패 → 감사 행도 롤백
        }
        append(tenant, entry(3));
        List<AuditRecord> stored = readAll(tenant);
        assertThat(stored).extracting(AuditRecord::seq).containsExactly(1L, 2L);
        assertThat(stored.get(1).entry().targetId()).isEqualTo("DISC-3");
        assertThat(AuditChain.breaks(stored)).isEmpty();
    }

    @Test
    void tamperingThatBypassesTheTriggerIsDetectedByRecomputation() {
        TenantId tenant = freshTenant();
        for (int i = 0; i < 3; i++) {
            append(tenant, entry(i));
        }
        // 테이블 소유자가 트리거를 끄고 detail을 고친다(설계서 부록 A-5의 "트리거 우회 가정").
        DB.seed(tenant.value(), c -> {
            SeedData.exec(c, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_append_only");
            SeedData.exec(c, "UPDATE audit_log SET detail = '{\"outcome\":\"INSERTED\",\"n\":999}'::jsonb WHERE tenant_id = ? AND seq = 2",
                    tenant.value());
            SeedData.exec(c, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_append_only");
        });
        assertThat(AuditChain.breaks(readAll(tenant))).containsExactly("seq 2 entryHash does not match its content");
    }
}
