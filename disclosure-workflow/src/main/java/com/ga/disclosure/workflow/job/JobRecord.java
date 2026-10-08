package com.ga.disclosure.workflow.job;

import com.ga.platform.core.tenant.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code async_job} 행(감싼 보고서 키는 싣지 않는다 — {@link JobStore#reportKey}). {@code params}는 JCS가 아닌 원문 JSON 텍스트. */
public record JobRecord(TenantId tenant, UUID jobId, JobKind kind, Status status, String requestedBy, Channel channel, String params,
                        Instant requestedAt, Optional<Instant> startedAt, Optional<Instant> finishedAt, Optional<String> resultRef,
                        Optional<String> reportSha256, Optional<String> errorCode) {

    public enum Status {
        QUEUED, RUNNING, SUCCEEDED, FAILED;

        public boolean active() {
            return this == QUEUED || this == RUNNING;
        }
    }

    /** 제출 경로(V12 {@code ck_job_channel}). */
    public enum Channel {
        HTTP, CLI
    }

    public JobRecord {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(resultRef, "resultRef");
        Objects.requireNonNull(reportSha256, "reportSha256");
        Objects.requireNonNull(errorCode, "errorCode");
    }

    /** 보고서 객체 키(V12 {@code ck_job_formats}: {@code {tenant}/reports/{job}}). */
    public static String reportKey(TenantId tenant, UUID jobId) {
        return tenant.value() + "/reports/" + jobId;
    }
}
