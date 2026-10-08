package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.authz.AuthorizationDenied;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.job.JobAlreadyRunningException;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobQueryService;
import com.ga.disclosure.workflow.job.JobRecord;
import com.ga.disclosure.workflow.job.JobRunner;
import com.ga.disclosure.workflow.job.JobWork;
import com.ga.platform.canonical.Sha256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 작업 실행기(6A 계획 §6.2, 설계서 §6 {@code job-states})를 실제 DB·전용 롤 잠금·SeaweedFS로: 보고서 암호화·열람, 같은 잠금 키의 겹침 거부(행 없음),
 * HTTP 제출의 409, 고아 정리(INTERRUPTED), <b>잠금 상실(승인 B1)</b> 두 경우, 실패·실행기 거부, 인가, 보고서 무결성, 잔여물 정리와 보고서.
 */
class JobRunnerIT {

    final JobSetup j = new JobSetup();

    @AfterEach
    void close() {
        j.close();
    }

    List<AuditRecord> jobAudit() {
        return j.s.audit().stream().filter(r -> r.entry().action().name().startsWith("JOB_")).toList();
    }

    static JobRunner.Outcome.Finished finished(JobRunner.Run<?> run) {
        assertThat(run.outcomes()).hasSize(1);
        return (JobRunner.Outcome.Finished) run.outcomes().getFirst();
    }

    @Test
    void aCliRunStoresAnEncryptedReportThatOnlyTheReadPathOpens() {
        String report = "{\"kind\":\"EXPIRE\",\"n\":1}";
        JobRunner.Run<String> run = j.runner().run(List.of(j.operator()), JobKind.EXPIRE, JobSetup.params().put("limit", 5), JobSetup.fixed(report));
        JobRunner.Outcome.Finished f = finished(run);
        assertThat(f.status()).isEqualTo(JobRecord.Status.SUCCEEDED);
        String key = JobRecord.reportKey(j.tenant(), f.jobId());
        assertThat(j.row(f.jobId())).isEqualTo("SUCCEEDED:-:" + key);
        assertThat(f.reportSha256()).contains(Sha256.of(report.getBytes(StandardCharsets.UTF_8)));

        byte[] stored = j.s.bucket.get(key);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("EXPIRE");
        assertThat(stored.length).isEqualTo(report.length() + 29);                // 0x01 ‖ nonce(12) ‖ 암호문 ‖ tag(16)
        assertThat(new String(j.queries.report(j.operator(), f.jobId()), StandardCharsets.UTF_8)).isEqualTo(report);

        JobRecord shown = j.queries.show(j.operator(), f.jobId());
        assertThat(shown.channel()).isEqualTo(JobRecord.Channel.CLI);
        assertThat(shown.requestedBy()).isEqualTo(JobSetup.OPERATOR);
        assertThat(shown.params()).isEqualTo("{\"limit\": 5}");
        assertThat(jobAudit()).extracting(r -> r.entry().action()).containsExactly(AuditAction.JOB_QUEUED, AuditAction.JOB_FINISHED,
                AuditAction.JOB_REPORT_VIEW);
        assertThat(jobAudit()).allSatisfy(r -> {
            assertThat(r.entry().actorRole()).isEqualTo("OPERATOR");
            assertThat(r.entry().targetKind()).isEqualTo("JOB");
            assertThat(r.entry().targetId()).isEqualTo(f.jobId().toString());
        });
    }

    /** 같은 잠금 키(파기·dry-run 포함)는 겹치지 않는다 — 두 번째는 행 없이 Busy(CLI)·409(HTTP). 다른 종류, 다른 테넌트의 같은 종류는 함께 돈다. */
    @Test
    void theSameLockKeyNeverRunsTwice() throws Exception {
        JobRunner runner = j.runner();
        JobSetup.Blocking first = new JobSetup.Blocking("{\"a\":1}");
        CompletableFuture<JobRunner.Run<String>> a = CompletableFuture.supplyAsync(
                () -> runner.run(List.of(j.operator()), JobKind.DESTROY, JobSetup.params(), first));
        first.awaitEntered();
        assertThat(runner.run(List.of(j.operator()), JobKind.DESTROY_DRY_RUN, JobSetup.params(), JobSetup.fixed("{}")).outcomes())
                .containsExactly(new JobRunner.Outcome.Busy(j.tenant()));
        assertThat(j.rows()).isEqualTo(1);
        assertThat(finished(runner.run(List.of(j.operator()), JobKind.RECONCILE, JobSetup.params(), JobSetup.fixed("{}"))).status())
                .as("another kind runs alongside").isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThatThrownBy(() -> j.runner(Thread.ofVirtual()::start, Map.of(JobKind.DESTROY, p -> JobSetup.fixed("{}")))
                .submit(j.operator(), JobKind.DESTROY, JobSetup.params())).as("a lock held by the CLI is a 409 over HTTP")
                .isInstanceOf(JobAlreadyRunningException.class);
        String other = SeedData.uniqueTenant("JOB2");
        j.s.w.db.seed(other, c -> SeedData.tenant(c, other));
        assertThat(finished(runner.run(List.of(Caller.cli(com.ga.platform.core.tenant.TenantId.of(other), JobSetup.OPERATOR)), JobKind.DESTROY,
                JobSetup.params(), JobSetup.fixed("{}"))).status()).as("the same kind runs for another tenant").isEqualTo(JobRecord.Status.SUCCEEDED);
        first.release.countDown();
        assertThat(finished(a.get(30, TimeUnit.SECONDS)).status()).isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThat(j.lockHolders(JobKind.DESTROY)).isZero();
    }

    /** HTTP 제출: QUEUED 행을 만들고 실행기에서 끝낸다. 도는 동안 같은 종류의 제출은 409(행 없음). */
    @Test
    void anHttpSubmissionRunsOnTheExecutorAndASecondOneConflicts() throws Exception {
        JobSetup.Blocking work = new JobSetup.Blocking("{\"http\":true}");
        JobRunner runner = j.runner(Thread.ofVirtual()::start, Map.of(JobKind.RECONCILE, p -> work));
        UUID id = runner.submit(j.operator(), JobKind.RECONCILE, JobSetup.params()).jobId();
        work.awaitEntered();
        assertThat(j.row(id)).startsWith("RUNNING:");
        assertThatThrownBy(() -> runner.submit(j.operator(), JobKind.RECONCILE, JobSetup.params())).isInstanceOf(JobAlreadyRunningException.class);
        assertThat(j.rows()).isEqualTo(1);
        work.release.countDown();
        awaitTerminal(id);
        assertThat(j.row(id)).startsWith("SUCCEEDED:");
        assertThatThrownBy(() -> runner.submit(j.operator(), JobKind.ANCHOR, JobSetup.params())).as("anchor is CLI only (Q7)")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.submit(j.operator(), JobKind.EXPIRE, JobSetup.params())).as("no handler registered")
                .isInstanceOf(IllegalArgumentException.class);
    }

    void awaitTerminal(UUID id) throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            String row = j.row(id);
            if (row.startsWith("SUCCEEDED") || row.startsWith("FAILED")) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job " + id + " never finished: " + j.row(id));
    }

    /** 잠금을 잡았는데 같은 키의 활성 행이 있으면 그 실행기는 죽었다 — FAILED(INTERRUPTED)로 닫고 감사한다. */
    @Test
    void aLeftoverActiveRowIsInterruptedByTheNextSubmission() {
        String t = j.tenant().value();
        UUID orphan = UUID.randomUUID();
        j.s.w.db.seed(t, c -> {
            SeedData.exec(c, """
                    INSERT INTO async_job (tenant_id, job_id, kind, status, requested_by, channel, requested_at)
                    VALUES (?, ?, 'DESTROY_DRY_RUN', 'QUEUED', 'scheduler@seed', 'HTTP', TIMESTAMPTZ '2026-09-23 09:00:00+09')
                    """, t, orphan);
            SeedData.exec(c, "UPDATE async_job SET status = 'RUNNING', started_at = TIMESTAMPTZ '2026-09-23 09:01:00+09' WHERE tenant_id = ? AND job_id = ?",
                    t, orphan);
        });
        JobRunner.Outcome.Finished f = finished(j.runner().run(List.of(j.operator()), JobKind.DESTROY, JobSetup.params(), JobSetup.fixed("{}")));
        assertThat(f.status()).isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThat(j.row(orphan)).isEqualTo("FAILED:INTERRUPTED:-");
        assertThat(jobAudit()).filteredOn(r -> r.entry().action() == AuditAction.JOB_INTERRUPTED).singleElement().satisfies(r -> {
            assertThat(r.entry().targetId()).isEqualTo(orphan.toString());
            assertThat(r.entry().detail().get("interruptedBy").asString()).isEqualTo(f.jobId().toString());
            assertThat(r.entry().detail().get("from").asString()).isEqualTo("RUNNING");
        });
    }

    /**
     * 승인 B1: 실행 중 잠금 커넥션이 끊기면(실행기 스레드는 살아 있다) 보고서 저장 직전 확인이 잡는다 — 옛 작업은 {@code FAILED(LOCK_LOST)}이고 보고서가
     * 없으며, 새 작업은 정상이다. 확인을 빼면 두 작업이 모두 SUCCEEDED가 된다(주입 기록은 커밋 본문).
     */
    @Test
    void aLostLockFailsTheOldJobWithoutAReportAndTheNextJobRuns() throws Exception {
        JobRunner runner = j.runner();
        JobSetup.Blocking old = new JobSetup.Blocking("{\"old\":true}");
        CompletableFuture<JobRunner.Run<String>> a = CompletableFuture.supplyAsync(
                () -> runner.run(List.of(j.operator()), JobKind.VERIFY_TENANT, JobSetup.params(), old));
        old.awaitEntered();
        assertThat(j.killLockHolder(JobKind.VERIFY_TENANT)).isEqualTo(1);
        old.release.countDown();
        JobRunner.Outcome.Finished lost = finished(a.get(30, TimeUnit.SECONDS));
        assertThat(lost.status()).isEqualTo(JobRecord.Status.FAILED);
        assertThat(lost.errorCode()).contains("LOCK_LOST");
        assertThat(j.row(lost.jobId())).isEqualTo("FAILED:LOCK_LOST:-");
        assertThat(j.s.bucket.exists(JobRecord.reportKey(j.tenant(), lost.jobId()))).as("no report for a lost lock").isFalse();

        JobRunner.Outcome.Finished next = finished(runner.run(List.of(j.operator()), JobKind.VERIFY_TENANT, JobSetup.params(),
                JobSetup.fixed("{\"new\":true}")));
        assertThat(next.status()).isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThat(j.s.count("SELECT count(*) FROM async_job WHERE tenant_id = ? AND status = 'SUCCEEDED'", j.tenant().value())).isEqualTo(1);
    }

    /**
     * 승인 B1 겹침: 잠금이 끊긴 사이 새 제출이 잠금을 잡아 옛 행을 INTERRUPTED로 닫고 끝까지 돈다. 그 뒤 깨어난 옛 실행기는 잠금을 잃었음을 보고 보고서를
     * 올리지 않으며, 조건부 전이라 새 제출이 닫은 행을 다시 쓰지 않는다.
     */
    @Test
    void anOldExecutorThatWakesAfterBeingInterruptedWritesNothing() throws Exception {
        JobRunner runner = j.runner();
        JobSetup.Blocking old = new JobSetup.Blocking("{\"old\":true}");
        CompletableFuture<JobRunner.Run<String>> a = CompletableFuture.supplyAsync(
                () -> runner.run(List.of(j.operator()), JobKind.EXPIRE, JobSetup.params(), old));
        old.awaitEntered();
        j.killLockHolder(JobKind.EXPIRE);
        JobRunner.Outcome.Finished next = finished(runner.run(List.of(j.operator()), JobKind.EXPIRE, JobSetup.params(),
                JobSetup.fixed("{\"new\":true}")));
        assertThat(next.status()).isEqualTo(JobRecord.Status.SUCCEEDED);

        old.release.countDown();
        JobRunner.Outcome.Finished stale = finished(a.get(30, TimeUnit.SECONDS));
        assertThat(stale.status()).isEqualTo(JobRecord.Status.FAILED);
        assertThat(stale.errorCode()).contains("INTERRUPTED");
        assertThat(j.row(stale.jobId())).isEqualTo("FAILED:INTERRUPTED:-");
        assertThat(j.s.bucket.exists(JobRecord.reportKey(j.tenant(), stale.jobId()))).isFalse();
        assertThat(j.row(next.jobId())).startsWith("SUCCEEDED:");
        assertThat(jobAudit()).filteredOn(r -> r.entry().action() == AuditAction.JOB_FINISHED
                && r.entry().targetId().equals(stale.jobId().toString())).as("the stale executor recorded nothing").isEmpty();
    }

    @Test
    void aFailingBodyEndsFailedWithoutAReportAndRethrows() {
        JobWork<String> boom = new JobWork<>() {
            @Override
            public String run(List<Caller> acquired) {
                throw new IllegalStateException("boom with no personal data");
            }

            @Override
            public byte[] report(String result, com.ga.platform.core.tenant.TenantId tenant) {
                throw new AssertionError();
            }
        };
        assertThatThrownBy(() -> j.runner().run(List.of(j.operator()), JobKind.RECONCILE, JobSetup.params(), boom))
                .isInstanceOf(IllegalStateException.class);
        UUID id = j.only().orElseThrow();
        assertThat(j.row(id)).isEqualTo("FAILED:EXECUTION_FAILED:-");
        assertThat(jobAudit()).filteredOn(r -> r.entry().action() == AuditAction.JOB_FINISHED).singleElement().satisfies(r -> {
            assertThat(r.entry().detail().get("exception").asString()).isEqualTo("IllegalStateException");
            assertThat(r.entry().detail().toString()).doesNotContain("boom");
        });
        assertThatThrownBy(() -> j.queries.report(j.operator(), id)).isInstanceOf(JobQueryService.ReportNotAvailableException.class);
    }

    @Test
    void anExecutorRejectionClosesTheQueuedRowAndReleasesTheLock() throws Exception {
        JobRunner rejecting = j.runner(command -> {
            throw new RejectedExecutionException("full");
        }, Map.of(JobKind.RECONCILE, p -> JobSetup.fixed("{}")));
        assertThatThrownBy(() -> rejecting.submit(j.operator(), JobKind.RECONCILE, JobSetup.params())).isInstanceOf(RejectedExecutionException.class);
        assertThat(j.row(j.only().orElseThrow())).isEqualTo("FAILED:REJECTED:-");
        assertThat(j.lockHolders(JobKind.RECONCILE)).isZero();
    }

    /** 권한 없는 호출자는 잠금도 행도 만들지 않는다(사람 역할에 파기 칸이 없다). */
    @Test
    void anUnauthorizedCallerTakesNoLockAndLeavesNoRow() {
        assertThatThrownBy(() -> j.runner().run(List.of(Callers.of(j.tenant(), WorkflowSetup.AGENT)), JobKind.DESTROY, JobSetup.params(),
                JobSetup.fixed("{}"))).isInstanceOf(AuthorizationDenied.class);
        assertThatThrownBy(() -> j.runner(Thread.ofVirtual()::start, Map.of(JobKind.DESTROY, p -> JobSetup.fixed("{}")))
                .submit(Callers.of(j.tenant(), WorkflowSetup.MANAGER), JobKind.DESTROY, JobSetup.params())).isInstanceOf(AuthorizationDenied.class);
        assertThat(j.rows()).isZero();
    }

    /** 저장된 보고서가 바뀌면 열람이 거부된다(손상 원문을 돌려주지 않는다). 다른 테넌트의 작업은 없는 대상(404). */
    @Test
    void aTamperedReportIsRefusedAndAnotherTenantsJobIsNotFound() {
        JobRunner.Outcome.Finished f = finished(j.runner().run(List.of(j.operator()), JobKind.EXPIRE, JobSetup.params(), JobSetup.fixed("{\"x\":1}")));
        String key = JobRecord.reportKey(j.tenant(), f.jobId());
        byte[] stored = j.s.bucket.get(key);
        stored[stored.length - 1] ^= 1;
        j.s.bucket.put(key, stored);
        assertThatThrownBy(() -> j.queries.report(j.operator(), f.jobId())).isInstanceOf(JobQueryService.ReportIntegrityException.class);
        assertThat(jobAudit()).noneMatch(r -> r.entry().action() == AuditAction.JOB_REPORT_VIEW);
        try (WorkflowSetup other = new WorkflowSetup()) {
            assertThatThrownBy(() -> j.queries.show(Caller.cli(other.tenant, JobSetup.OPERATOR), f.jobId()))
                    .isInstanceOf(AuthorizationDenied.class);
        }
    }

    /** 잔여물 정리는 작업 행이 가리키는 보고서를 지우지 않고, 참조 없는 보고서 객체(잠금 상실 잔여물)는 유예 뒤 지운다. */
    @Test
    void garbageCollectionKeepsReferencedReportsAndRemovesOrphans() {
        JobRunner.Outcome.Finished f = finished(j.runner().run(List.of(j.operator()), JobKind.EXPIRE, JobSetup.params(), JobSetup.fixed("{}")));
        String kept = JobRecord.reportKey(j.tenant(), f.jobId());
        String orphan = JobRecord.reportKey(j.tenant(), UUID.randomUUID());
        j.s.bucket.put(orphan, new byte[] {1, 2, 3});
        ArtifactService later = j.s.artifactsAt(java.time.Clock.offset(java.time.Clock.systemUTC(), Duration.ofHours(25)), j.s.bucket);   // 객체 시각은 저장소 벽시계
        ArtifactService.GcReport gc = later.gc(Caller.cli(j.tenant(), JobSetup.OPERATOR), SealSetup.grace());
        assertThat(gc.deleted()).containsExactly(orphan);
        assertThat(j.s.bucket.exists(kept)).isTrue();
    }

    @Test
    void releasedLocksLeaveNoHolder() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        j.runner().run(List.of(j.operator()), JobKind.IDEMPOTENCY_PURGE, JobSetup.params(), JobSetup.fixed("{}"));
        done.countDown();
        assertThat(j.lockHolders(JobKind.IDEMPOTENCY_PURGE)).isZero();
    }
}
