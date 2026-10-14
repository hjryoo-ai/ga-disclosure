package com.ga.disclosure.infra;

import com.ga.disclosure.infra.crypto.ReportCipher;
import com.ga.disclosure.infra.jobs.JobLockGateway;
import com.ga.disclosure.infra.persistence.AsyncJobRepository;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.job.JobHandlers;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobQueryService;
import com.ga.disclosure.workflow.job.JobRunner;
import com.ga.disclosure.workflow.job.JobWork;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 작업 실행기 조립(6A 계획 §6): {@link SealSetup} 위에 실제 작업 저장소·전용 롤 잠금({@code disclosure_job_lock})·보고서 암호화(같은 로컬 KEK)·SeaweedFS
 * 버킷. 실행기는 테스트가 고른다(기본은 가상 스레드). 잠금 커넥션 강제 종료는 슈퍼유저가 {@code pg_locks}에서 그 키의 백엔드를 찾아 끊는다.
 */
final class JobSetup implements AutoCloseable {

    static final String OPERATOR = "ops-jobs@test";
    static final JsonMapper JSON = JsonMapper.builder().build();

    final SealSetup s;
    final AsyncJobRepository jobs;
    final JobLockGateway locks;
    final ReportCipher cipher;
    final JobQueryService queries;

    JobSetup() {
        this.s = new SealSetup();
        this.jobs = new AsyncJobRepository(s.w.gateway);
        PostgresHarness db = s.w.db;
        this.locks = new JobLockGateway(db.jdbcUrl(), PostgresHarness.JOB_LOCK, PostgresHarness.JOB_LOCK_PASSWORD);
        this.cipher = new ReportCipher(s.w.keys);
        this.queries = new JobQueryService(jobs, cipher, s.bucket, s.w.audit, s.w.tx, s.w.clock, Callers.authz(s.w.clock),
                com.ga.disclosure.infra.crypto.CursorCodec.ephemeral());
    }

    TenantId tenant() {
        return s.w.tenant;
    }

    Caller operator() {
        return Caller.cli(tenant(), OPERATOR);
    }

    JobRunner runner(Executor executor, Map<JobKind, Function<ObjectNode, JobWork<?>>> handlers) {
        return new JobRunner(jobs, locks, cipher, s.bucket, s.w.audit, s.w.tx, s.w.clock, UUID::randomUUID, Callers.authz(s.w.clock), executor,
                new JobHandlers(handlers), com.ga.disclosure.workflow.metrics.OperationalMetrics.NONE);
    }

    JobRunner runner() {
        return runner(Thread.ofVirtual()::start, Map.of());
    }

    static ObjectNode params() {
        return JSON.createObjectNode();
    }

    /** 고정 보고서를 내는 본체. */
    static JobWork<String> fixed(String reportJson) {
        return new JobWork<>() {
            @Override
            public String run(List<Caller> acquired) {
                return reportJson;
            }

            @Override
            public byte[] report(String result, TenantId tenant) {
                return result.getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    /** 들어오면 {@code entered}를 내리고 {@code release}까지 기다리는 본체(실행 중인 작업을 만든다). */
    static final class Blocking implements JobWork<String> {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final String reportJson;

        Blocking(String reportJson) {
            this.reportJson = reportJson;
        }

        @Override
        public String run(List<Caller> acquired) {
            runs.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return reportJson;
        }

        @Override
        public byte[] report(String result, TenantId tenant) {
            return result.getBytes(StandardCharsets.UTF_8);
        }

        void awaitEntered() throws InterruptedException {
            if (!entered.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("the job never started");
            }
        }
    }

    /** 이 테넌트·잠금 키의 잠금을 쥔 백엔드를 끊고, 잠금이 풀릴 때까지 기다린다. 끊은 백엔드 수. */
    int killLockHolder(JobKind kind) throws Exception {
        String key = JobLockGateway.keyText(tenant(), kind);
        int killed = 0;
        try (var c = s.w.db.superuserDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT pg_terminate_backend(l.pid)
                       FROM pg_locks l, (SELECT hashtextextended(?, 0) AS k) key
                      WHERE l.locktype = 'advisory' AND l.objsubid = 1 AND l.granted
                        AND l.classid::bigint = ((key.k >> 32) & 4294967295) AND l.objid::bigint = (key.k & 4294967295)
                     """)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    killed += rs.getBoolean(1) ? 1 : 0;
                }
            }
        }
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (lockHolders(kind) > 0) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("lock still held after terminate");
            }
            Thread.sleep(50);
        }
        return killed;
    }

    long lockHolders(JobKind kind) throws Exception {
        try (var c = s.w.db.superuserDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*)
                       FROM pg_locks l, (SELECT hashtextextended(?, 0) AS k) key
                      WHERE l.locktype = 'advisory' AND l.objsubid = 1 AND l.granted
                        AND l.classid::bigint = ((key.k >> 32) & 4294967295) AND l.objid::bigint = (key.k & 4294967295)
                     """)) {
            ps.setString(1, JobLockGateway.keyText(tenant(), kind));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    String row(UUID jobId) {
        return s.text("SELECT status || ':' || coalesce(error_code, '-') || ':' || coalesce(result_ref, '-') FROM async_job"
                + " WHERE tenant_id = ? AND job_id = ?", tenant().value(), jobId);
    }

    long rows() {
        return s.count("SELECT count(*) FROM async_job WHERE tenant_id = ?", tenant().value());
    }

    Optional<UUID> only() {
        List<com.ga.disclosure.workflow.job.JobRecord> all = s.w.in(() -> jobs.recent(100, Optional.empty()));
        return all.size() == 1 ? Optional.of(all.getFirst().jobId()) : Optional.empty();
    }

    @Override
    public void close() {
        s.close();
    }
}
