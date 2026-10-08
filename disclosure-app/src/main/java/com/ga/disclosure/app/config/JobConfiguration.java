package com.ga.disclosure.app.config;

import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.infra.crypto.ReportCipher;
import com.ga.disclosure.infra.jobs.JobLockGateway;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.ExpireService;
import com.ga.disclosure.workflow.disclosure.NotificationDispatcher;
import com.ga.disclosure.workflow.idempotency.IdempotencyPurge;
import com.ga.disclosure.workflow.idempotency.IdempotencyService;
import com.ga.disclosure.workflow.idempotency.IdempotencyStore;
import com.ga.disclosure.workflow.job.JobHandlers;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobLockPort;
import com.ga.disclosure.workflow.job.JobQueryService;
import com.ga.disclosure.workflow.job.JobRunner;
import com.ga.disclosure.workflow.job.JobStore;
import com.ga.disclosure.workflow.job.JobWork;
import com.ga.disclosure.workflow.job.ReportCryptoPort;
import com.ga.disclosure.workflow.job.StandardJobs;
import com.ga.disclosure.workflow.retention.DestructionJob;
import com.ga.disclosure.workflow.verify.TenantVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 6A 작업 조립(계획 §6): 잠금은 전용 롤 {@code disclosure_job_lock}(설정 {@code ga.job-lock.*}, 풀 없음), 보고서는 테넌트 저장소 {@code reports/}에 보고서별
 * DEK로 암호화, HTTP 제출은 가상 스레드 실행기. HTTP 처리기는 앵커를 등록하지 않는다(승인 Q7).
 */
@Configuration
public class JobConfiguration {

    static final int MAX_EXPIRE_LIMIT = 10_000;
    static final int MAX_RECONCILE_LIMIT = 10_000;
    static final int MAX_DESTROY_LIMIT = 1_000;
    static final int MAX_NOTIFY_LIMIT = 1_000;
    static final int MAX_PURGE_LIMIT = 100_000;

    @Bean
    public JobLockPort jobLockGateway(@Value("${ga.job-lock.url}") String url, @Value("${ga.job-lock.username}") String username,
                                      @Value("${ga.job-lock.password}") String password) {
        return new JobLockGateway(url, username, password);
    }

    @Bean
    public ReportCryptoPort reportCipher(KeyProviderPort keys) {
        return new ReportCipher(keys);
    }

    @Bean(destroyMethod = "close")
    public ExecutorService jobExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public JobHandlers jobHandlers(ExpireService expiry, ArtifactService artifacts, DestructionJob destruction, TenantVerifier verifier,
                                   NotificationDispatcher dispatcher, IdempotencyPurge purge, Clock clock,
                                   @Value("${ga.tsa.trust-pem:build/demo/tsa-trust.pem}") String trustPem) {
        Map<JobKind, Function<ObjectNode, JobWork<?>>> h = new EnumMap<>(JobKind.class);
        h.put(JobKind.EXPIRE, p -> {
            StandardJobs.only(p, Set.of("asOf", "limit"));
            return StandardJobs.expire(expiry, StandardJobs.instant(p, "asOf").orElseGet(clock::instant),
                    StandardJobs.limit(p, StandardJobs.DEFAULT_EXPIRE_LIMIT, MAX_EXPIRE_LIMIT));
        });
        h.put(JobKind.RECONCILE, p -> {
            StandardJobs.only(p, Set.of("limit"));
            return StandardJobs.reconcile(artifacts, StandardJobs.limit(p, StandardJobs.DEFAULT_RECONCILE_LIMIT, MAX_RECONCILE_LIMIT));
        });
        for (JobKind kind : new JobKind[] {JobKind.DESTROY, JobKind.DESTROY_DRY_RUN}) {
            h.put(kind, p -> {
                StandardJobs.only(p, Set.of("asOf", "limit"));
                return StandardJobs.destroy(destruction, StandardJobs.instant(p, "asOf").orElseGet(clock::instant), kind == JobKind.DESTROY_DRY_RUN,
                        StandardJobs.limit(p, StandardJobs.DEFAULT_DESTROY_LIMIT, MAX_DESTROY_LIMIT));
            });
        }
        h.put(JobKind.NOTIFY, p -> {
            StandardJobs.only(p, Set.of("limit"));
            return StandardJobs.notify(dispatcher, StandardJobs.limit(p, StandardJobs.DEFAULT_NOTIFY_LIMIT, MAX_NOTIFY_LIMIT));
        });
        h.put(JobKind.IDEMPOTENCY_PURGE, p -> {
            StandardJobs.only(p, Set.of("limit"));
            return StandardJobs.idempotencyPurge(purge, StandardJobs.limit(p, StandardJobs.DEFAULT_PURGE_LIMIT, MAX_PURGE_LIMIT));
        });
        h.put(JobKind.VERIFY_TENANT, p -> {
            StandardJobs.only(p, Set.of());
            return StandardJobs.verifyTenant(verifier, readIfPresent(Path.of(trustPem)));
        });
        return new JobHandlers(h);
    }

    private static byte[] readIfPresent(Path pem) {
        if (!Files.isRegularFile(pem)) {
            return null;
        }
        try {
            return Files.readAllBytes(pem);
        } catch (IOException e) {
            throw new UncheckedIOException("ga.tsa.trust-pem is not readable: " + pem, e);
        }
    }

    @Bean
    public IdempotencyService idempotencyService(IdempotencyStore store, RuleResolver rules, WorkflowTransactions tx, Clock clock) {
        return new IdempotencyService(store, rules, tx, clock);
    }

    @Bean
    public IdempotencyPurge idempotencyPurge(IdempotencyStore store, AuthorizationPort authz, WorkflowTransactions tx, Clock clock) {
        return new IdempotencyPurge(store, authz, tx, clock);
    }

    @Bean
    public JobRunner jobRunner(JobStore store, JobLockPort locks, ReportCryptoPort crypto, ArtifactStore storage, AuditPort audit,
                               WorkflowTransactions tx, Clock clock, AuthorizationPort authz, ExecutorService jobExecutor, JobHandlers handlers) {
        return new JobRunner(store, locks, crypto, storage, audit, tx, clock, UUID::randomUUID, authz, jobExecutor, handlers);
    }

    @Bean
    public JobQueryService jobQueryService(JobStore store, ReportCryptoPort crypto, ArtifactStore storage, AuditPort audit, WorkflowTransactions tx,
                                           Clock clock, AuthorizationPort authz) {
        return new JobQueryService(store, crypto, storage, audit, tx, clock, authz);
    }
}
