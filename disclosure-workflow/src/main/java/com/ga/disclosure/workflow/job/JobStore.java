package com.ga.disclosure.workflow.job;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code async_job} 포트(바인딩된 테넌트). 상태를 바꾸는 메서드는 전부 <b>조건부</b>다 — 기대한 상태가 아니면 0행이고 {@code false}를 돌려준다
 * (잠금을 잃은 옛 실행기가 새 제출이 닫은 행을 다시 쓰지 못한다, 승인 B1). 허용 전이는 GD121이 한 번 더 강제한다.
 */
public interface JobStore {

    void insertQueued(JobRecord job);

    /** 같은 잠금 키({@link JobKind#lockKind()})의 활성(QUEUED·RUNNING) 행. */
    List<JobRecord> active(JobKind lockKind);

    boolean markRunning(UUID jobId, Instant startedAt);

    boolean succeed(UUID jobId, Instant finishedAt, String resultRef, String reportSha256, byte[] reportKeyWrapped, String reportKekId);

    /** {@code from} 상태일 때만 FAILED로. */
    boolean fail(UUID jobId, JobRecord.Status from, Instant finishedAt, JobError error);

    Optional<JobRecord> find(UUID jobId);

    /** 보고서 키 재료(SUCCEEDED만). */
    Optional<ReportKey> reportKey(UUID jobId);

    /** 최근 순(요청 시각 내림차순, 같은 시각은 작업 ID 내림차순). {@code before}가 있으면 그 행 다음부터(키셋). */
    List<JobRecord> recent(int limit, Optional<Position> before);

    record ReportKey(String kekId, byte[] wrapped) {
        public ReportKey {
            wrapped = wrapped.clone();
        }

        @Override
        public byte[] wrapped() {
            return wrapped.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ReportKey k && kekId.equals(k.kekId) && java.util.Arrays.equals(wrapped, k.wrapped);
        }

        @Override
        public int hashCode() {
            return kekId.hashCode();
        }

        @Override
        public String toString() {
            return "ReportKey[" + kekId + "]";
        }
    }

    /** 목록 키셋 위치(서명 커서는 API 층이 이 값을 감싼다 — 승인 Q5). */
    record Position(Instant requestedAt, UUID jobId) {
    }
}
