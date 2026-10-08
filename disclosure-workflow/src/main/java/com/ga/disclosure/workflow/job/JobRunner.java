package com.ga.disclosure.workflow.job;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Channel;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * 작업 실행기(6A 계획 §6.2, 설계서 §6 {@code job-states}) — CLI({@link #run}, 동기)와 HTTP({@link #submit}, 실행기에 넘기고 즉시 반환)가 같은 길을 지난다.
 * <ol>
 *   <li>인가(종류의 행위) — 잠금보다 먼저(권한 없는 호출자가 잠금을 잡지 못하게).</li>
 *   <li>잠금(테넌트·잠금 키, 기다리지 않음). 못 잡으면 행을 만들지 않는다(HTTP 409, CLI는 그 테넌트만 {@link Outcome.Busy}).</li>
 *   <li>같은 잠금 키의 활성 행이 남아 있으면 그 실행기는 잠금을 잃었다 — {@code FAILED(INTERRUPTED)}로 닫고 {@code JOB_INTERRUPTED}. QUEUED 행과
 *       {@code JOB_QUEUED}를 한 트랜잭션에.</li>
 *   <li>RUNNING → 유스케이스(자기 트랜잭션들) → 테넌트마다 보고서 암호화·저장 → SUCCEEDED + {@code JOB_FINISHED}. 예외는 FAILED.</li>
 *   <li><b>잠금 보유 확인(승인 B1)</b>: 보고서 저장 직전과 종단 전이 직전에 {@link JobLockPort.Held#stillHeld()}. 잃었으면 보고서를 저장하지 않고
 *       {@code FAILED(LOCK_LOST)}. 종단 전이는 조건부라 새 제출이 이미 닫은 행은 다시 쓰지 않는다(로그 코드만).</li>
 *   <li>잠금은 {@code finally}에서 푼다.</li>
 * </ol>
 * 작업이 끝까지 돌았으면 유스케이스 결과의 실패(파기 실패·검증 불일치·앵커 실패)는 보고서가 말한다 — 작업은 SUCCEEDED다. FAILED는 보고서가 없다(V12).
 */
public final class JobRunner {

    static final String JOB_TARGET = "JOB";
    private static final System.Logger LOG = System.getLogger(JobRunner.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JobStore store;
    private final JobLockPort locks;
    private final ReportCryptoPort crypto;
    private final ArtifactStore storage;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final AuthorizationPort authz;
    private final Executor executor;
    private final JobHandlers handlers;

    public JobRunner(JobStore store, JobLockPort locks, ReportCryptoPort crypto, ArtifactStore storage, AuditPort audit,
                     WorkflowTransactions transactions, Clock clock, Supplier<UUID> ids, AuthorizationPort authz, Executor executor, JobHandlers handlers) {
        this.store = Objects.requireNonNull(store, "store");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.handlers = Objects.requireNonNull(handlers, "handlers");
    }

    /** 테넌트별 결과. */
    public sealed interface Outcome {

        TenantId tenant();

        /** 같은 잠금 키의 작업이 이미 돈다 — 행을 만들지 않았다. */
        record Busy(TenantId tenant) implements Outcome {
        }

        /** 작업 행의 종단(또는 잠금을 잃어 다른 제출이 닫은 상태). */
        record Finished(TenantId tenant, UUID jobId, JobRecord.Status status, Optional<String> errorCode, Optional<String> reportSha256)
                implements Outcome {
        }
    }

    /** CLI 실행 결과: 유스케이스 결과(잠금을 하나도 못 잡았으면 빈 값)와 테넌트별 결과(테넌트 ID 순). */
    public record Run<R>(Optional<R> result, List<Outcome> outcomes) {
        public Run {
            Objects.requireNonNull(result, "result");
            outcomes = List.copyOf(outcomes);
        }
    }

    /** 잠금을 잡고 QUEUED 행을 만든 작업 하나. */
    private record Started(Caller caller, Actor actor, UUID jobId, JobKind kind, JobLockPort.Held lock, JobRecord queued) {
        TenantId tenant() {
            return caller.tenant();
        }
    }

    /**
     * 운영자 CLI(동기): 테넌트 ID 순으로 인가·잠금을 하나씩 잡고, 잡은 테넌트들로 본체를 한 번 부른다. 잠긴 테넌트는 그 테넌트만 {@link Outcome.Busy}.
     * 본체가 예외면 잡은 작업 전부 {@code FAILED(EXECUTION_FAILED)}로 닫고 예외를 다시 던진다.
     */
    @UseCaseEntry({Action.ANCHOR_RUN, Action.DISCLOSURE_EXPIRE, Action.ARTIFACT_RECONCILE, Action.DESTROY, Action.DESTROY_DRY_RUN,
            Action.VERIFY_TENANT, Action.NOTIFY_DISPATCH, Action.IDEMPOTENCY_PURGE, Action.FLAG_SLA_SWEEP, Action.CONTRACT_LINK_IMPORT,
            Action.CONTRACT_LINK_UNMATCHED_PURGE})
    public <R> Run<R> run(List<Caller> callers, JobKind kind, ObjectNode params, JobWork<R> work) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(work, "work");
        Action action = switch (kind) {
            case ANCHOR -> Action.ANCHOR_RUN;
            case EXPIRE -> Action.DISCLOSURE_EXPIRE;
            case RECONCILE -> Action.ARTIFACT_RECONCILE;
            case DESTROY -> Action.DESTROY;
            case DESTROY_DRY_RUN -> Action.DESTROY_DRY_RUN;
            case VERIFY_TENANT -> Action.VERIFY_TENANT;
            case NOTIFY -> Action.NOTIFY_DISPATCH;
            case IDEMPOTENCY_PURGE -> Action.IDEMPOTENCY_PURGE;
            case FLAG_SLA_SWEEP -> Action.FLAG_SLA_SWEEP;
            case CONTRACT_LINK_IMPORT -> Action.CONTRACT_LINK_IMPORT;
            case CONTRACT_LINK_UNMATCHED_PURGE -> Action.CONTRACT_LINK_UNMATCHED_PURGE;
        };
        List<Caller> sorted = callers.stream().sorted(Comparator.comparing(c -> c.tenant().value())).toList();
        if (sorted.stream().map(Caller::tenant).distinct().count() != sorted.size()) {
            throw new IllegalArgumentException("one job per tenant");
        }
        List<Outcome> outcomes = new ArrayList<>();
        List<Started> started = new ArrayList<>();
        try {
            for (Caller caller : sorted) {
                Actor actor = transactions.inTenant(caller.tenant(), () -> authz.require(caller, action, Target.none()));
                Optional<JobLockPort.Held> lock = locks.tryAcquire(caller.tenant(), kind.lockKind());
                if (lock.isEmpty()) {
                    outcomes.add(new Outcome.Busy(caller.tenant()));
                    continue;
                }
                started.add(begin(caller, actor, kind, params, lock.get()));
            }
            if (started.isEmpty()) {
                return new Run<>(Optional.empty(), outcomes);
            }
            started.forEach(this::markRunning);
            R result = execute(started, work, outcomes);
            outcomes.sort(Comparator.comparing(o -> o.tenant().value()));
            return new Run<>(Optional.of(result), outcomes);
        } finally {
            started.forEach(s -> s.lock().close());
        }
    }

    /**
     * HTTP 제출(202): 인가 → 매개변수(400) → 잠금(409 {@link JobAlreadyRunningException}) → QUEUED 행 → 실행기. 돌려주는 것은 만든 QUEUED 행이다. 실행기가 받지 않으면
     * {@code QUEUED→FAILED(REJECTED)}로 닫고 {@link RejectedExecutionException}을 다시 던진다. 앵커는 HTTP가 아니다(승인 Q7).
     */
    @UseCaseEntry({Action.DISCLOSURE_EXPIRE, Action.ARTIFACT_RECONCILE, Action.DESTROY, Action.DESTROY_DRY_RUN, Action.VERIFY_TENANT,
            Action.NOTIFY_DISPATCH, Action.IDEMPOTENCY_PURGE, Action.FLAG_SLA_SWEEP, Action.CONTRACT_LINK_UNMATCHED_PURGE})
    public JobRecord submit(Caller caller, JobKind kind, ObjectNode params) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(params, "params");
        Action action = switch (kind) {
            case ANCHOR -> throw new IllegalArgumentException("ANCHOR runs only from the operator CLI (6A approval Q7)");
            case EXPIRE -> Action.DISCLOSURE_EXPIRE;
            case RECONCILE -> Action.ARTIFACT_RECONCILE;
            case DESTROY -> Action.DESTROY;
            case DESTROY_DRY_RUN -> Action.DESTROY_DRY_RUN;
            case VERIFY_TENANT -> Action.VERIFY_TENANT;
            case NOTIFY -> Action.NOTIFY_DISPATCH;
            case IDEMPOTENCY_PURGE -> Action.IDEMPOTENCY_PURGE;
            case FLAG_SLA_SWEEP -> Action.FLAG_SLA_SWEEP;
            case CONTRACT_LINK_IMPORT -> throw new IllegalArgumentException("CONTRACT_LINK_IMPORT carries its batch in the request body");
            case CONTRACT_LINK_UNMATCHED_PURGE -> Action.CONTRACT_LINK_UNMATCHED_PURGE;
        };
        Actor actor = transactions.inTenant(caller.tenant(), () -> authz.require(caller, action, Target.none()));
        JobWork<?> work = handlers.work(kind, params)
                .orElseThrow(() -> new IllegalArgumentException("job kind " + kind + " is not available over HTTP"));
        return queue(caller, actor, kind, params, work);
    }

    /** 본문이 입력인 작업: 작업 행에 남길 요약 매개변수와 본체(입력을 메모리로 잡는다). */
    public record BodyJob(ObjectNode summaryParams, JobWork<?> work) {
        public BodyJob {
            Objects.requireNonNull(summaryParams, "summaryParams");
            Objects.requireNonNull(work, "work");
        }
    }

    /**
     * 본문이 입력인 작업의 HTTP 제출(6B 계약 연결 배치): 인가 → 본문 해석({@code prepare} — 형식 오류는 400, 인가 뒤라 권한 없는 주체에게는 같은 404) →
     * 잠금 → QUEUED 행 → 실행기. 작업 행의 {@code params}에는 번호 없는 요약(출처·배치 ID·건수·해시)만 남긴다.
     */
    @UseCaseEntry(Action.CONTRACT_LINK_IMPORT)
    public JobRecord submitWithBody(Caller caller, JobKind kind, java.util.function.Supplier<BodyJob> prepare) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(prepare, "prepare");
        if (kind != JobKind.CONTRACT_LINK_IMPORT) {
            throw new IllegalArgumentException("only CONTRACT_LINK_IMPORT takes its input from the request body");
        }
        Actor actor = transactions.inTenant(caller.tenant(), () -> authz.require(caller, Action.CONTRACT_LINK_IMPORT, Target.none()));
        BodyJob job = prepare.get();
        return queue(caller, actor, kind, job.summaryParams(), job.work());
    }

    private JobRecord queue(Caller caller, Actor actor, JobKind kind, ObjectNode params, JobWork<?> work) {
        JobLockPort.Held lock = locks.tryAcquire(caller.tenant(), kind.lockKind()).orElseThrow(() -> new JobAlreadyRunningException(kind));
        Started started;
        try {
            started = begin(caller, actor, kind, params, lock);
        } catch (RuntimeException e) {
            lock.close();
            throw e;
        }
        try {
            executor.execute(() -> runInBackground(started, work));
        } catch (RejectedExecutionException e) {
            try {
                fail(started, JobRecord.Status.QUEUED, JobError.REJECTED, e);
            } finally {
                lock.close();
            }
            throw e;
        }
        return started.queued();
    }

    private <R> void runInBackground(Started started, JobWork<R> work) {
        try {
            markRunning(started);
            execute(List.of(started), work, new ArrayList<>());
        } catch (RuntimeException e) {
            // 행은 이미 FAILED로 닫혔다(execute) — 실행기 스레드 밖으로 던질 곳이 없다
            LOG.log(System.Logger.Level.WARNING, "JOB_BACKGROUND_FAILED " + started.jobId() + " " + e.getClass().getSimpleName());
        } finally {
            started.lock().close();
        }
    }

    // ------------------------------------------------------------------ 단계

    private Started begin(Caller caller, Actor actor, JobKind kind, ObjectNode params, JobLockPort.Held lock) {
        UUID jobId = ids.get();
        JobRecord.Channel channel = caller.channel() == Channel.CLI ? JobRecord.Channel.CLI : JobRecord.Channel.HTTP;
        String paramsJson = JSON.writeValueAsString(params);
        JobRecord queued = transactions.inTenant(caller.tenant(), () -> {
            var now = clock.instant();
            for (JobRecord old : store.active(kind.lockKind())) {
                if (store.fail(old.jobId(), old.status(), now, JobError.INTERRUPTED)) {
                    record(actor, AuditAction.JOB_INTERRUPTED, old.jobId(), JSON.createObjectNode().put("kind", old.kind().name())
                            .put("from", old.status().name()).put("interruptedBy", jobId.toString()));
                }
            }
            JobRecord job = new JobRecord(caller.tenant(), jobId, kind, JobRecord.Status.QUEUED, caller.subject(), channel, paramsJson, now,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            store.insertQueued(job);
            record(actor, AuditAction.JOB_QUEUED, jobId, JSON.createObjectNode().put("kind", kind.name()).put("channel", channel.name())
                    .set("params", params.deepCopy()));
            return job;
        });
        return new Started(caller, actor, jobId, kind, lock, queued);
    }

    private void markRunning(Started s) {
        boolean moved = transactions.inTenant(s.tenant(), () -> store.markRunning(s.jobId(), clock.instant()));
        if (!moved) {
            throw new IllegalStateException("job " + s.jobId() + " left QUEUED while its lock was held");
        }
    }

    private <R> R execute(List<Started> started, JobWork<R> work, List<Outcome> outcomes) {
        R result;
        try {
            result = work.run(started.stream().map(Started::caller).toList());
        } catch (RuntimeException e) {
            started.forEach(s -> outcomes.add(fail(s, JobRecord.Status.RUNNING, JobError.EXECUTION_FAILED, e)));
            throw e;
        }
        started.forEach(s -> outcomes.add(complete(s, work, result)));
        return result;
    }

    private <R> Outcome complete(Started s, JobWork<R> work, R result) {
        byte[] plaintext;
        try {
            plaintext = work.report(result, s.tenant());
        } catch (RuntimeException e) {
            return fail(s, JobRecord.Status.RUNNING, JobError.REPORT_STORE_FAILED, e);
        }
        if (!s.lock().stillHeld()) {
            return fail(s, JobRecord.Status.RUNNING, JobError.LOCK_LOST, null);
        }
        String key = JobRecord.reportKey(s.tenant(), s.jobId());
        ReportCryptoPort.Sealed sealed;
        try {
            sealed = crypto.seal(s.tenant(), s.jobId(), plaintext);
            storage.put(key, sealed.ciphertext());
        } catch (RuntimeException e) {
            return fail(s, JobRecord.Status.RUNNING, JobError.REPORT_STORE_FAILED, e);
        }
        if (!s.lock().stillHeld()) {
            return fail(s, JobRecord.Status.RUNNING, JobError.LOCK_LOST, null);      // 올린 객체는 참조 행이 없다 — 잔여물 정리가 지운다
        }
        String sha = Sha256.of(plaintext);
        boolean done = transactions.inTenant(s.tenant(), () -> {
            if (!store.succeed(s.jobId(), clock.instant(), key, sha, sealed.key().wrapped(), sealed.key().kekId())) {
                return false;
            }
            record(s.actor(), AuditAction.JOB_FINISHED, s.jobId(), JSON.createObjectNode().put("status", JobRecord.Status.SUCCEEDED.name())
                    .put("reportSha256", sha));
            return true;
        });
        return done ? new Outcome.Finished(s.tenant(), s.jobId(), JobRecord.Status.SUCCEEDED, Optional.empty(), Optional.of(sha)) : stale(s);
    }

    private Outcome fail(Started s, JobRecord.Status from, JobError error, RuntimeException causeOrNull) {
        boolean done = transactions.inTenant(s.tenant(), () -> {
            if (!store.fail(s.jobId(), from, clock.instant(), error)) {
                return false;
            }
            ObjectNode detail = JSON.createObjectNode().put("status", JobRecord.Status.FAILED.name()).put("errorCode", error.name());
            if (causeOrNull != null) {
                detail.put("exception", causeOrNull.getClass().getSimpleName());
            }
            record(s.actor(), AuditAction.JOB_FINISHED, s.jobId(), detail);
            return true;
        });
        return done ? new Outcome.Finished(s.tenant(), s.jobId(), JobRecord.Status.FAILED, Optional.of(error.name()), Optional.empty()) : stale(s);
    }

    /** 다른 제출이 이 행을 이미 닫았다(잠금을 잃은 옛 실행기) — 아무것도 쓰지 않는다. */
    private Outcome stale(Started s) {
        LOG.log(System.Logger.Level.WARNING, "JOB_STALE_FINISH " + s.jobId());
        JobRecord now = transactions.inTenant(s.tenant(), () -> store.find(s.jobId())).orElseThrow();
        return new Outcome.Finished(s.tenant(), s.jobId(), now.status(), now.errorCode(), now.reportSha256());
    }

    private void record(Actor actor, AuditAction action, UUID jobId, ObjectNode detail) {
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), action, JOB_TARGET, jobId.toString(), detail));
    }
}
