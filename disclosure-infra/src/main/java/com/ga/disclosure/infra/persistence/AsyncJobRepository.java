package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.job.JobError;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobRecord;
import com.ga.disclosure.workflow.job.JobStore;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 작업 리소스(V12 {@code async_job}). 상태 전이는 전부 조건부 UPDATE(기대한 상태일 때만 1행)이고, 허용 전이·불변 컬럼은 GD121이 한 번 더 강제한다.
 * 삭제 없음(앱 롤에 DELETE 권한이 없다). 시작·종단 시각은 이 프로세스의 시계와 행의 앞선 시각 중 늦은 쪽이다 — 시계가 앞선 다른 노드가 남긴 고아를 닫을 때
 * {@code ck_job_times}(시각 단조)에 걸려 새 작업이 영영 시작하지 못하는 일이 없게.
 */
@Repository
public class AsyncJobRepository extends TenantScopedRepository implements JobStore {

    private static final String COLUMNS = "tenant_id, job_id, kind, status, requested_by, channel, params::text AS params, requested_at, started_at, "
            + "finished_at, result_ref, report_sha256, error_code";
    private static final RowMapper<JobRecord> MAPPER = (rs, n) -> new JobRecord(TenantId.of(rs.getString("tenant_id")),
            rs.getObject("job_id", UUID.class), JobKind.valueOf(rs.getString("kind")), JobRecord.Status.valueOf(rs.getString("status")),
            rs.getString("requested_by"), JobRecord.Channel.valueOf(rs.getString("channel")), rs.getString("params"),
            rs.getTimestamp("requested_at").toInstant(), instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
            Optional.ofNullable(rs.getString("result_ref")), Optional.ofNullable(rs.getString("report_sha256")),
            Optional.ofNullable(rs.getString("error_code")));

    public AsyncJobRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    private static Optional<Instant> instant(Timestamp t) {
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    @Override
    public void insertQueued(JobRecord job) {
        Map<String, Object> p = new HashMap<>();
        p.put("jobId", job.jobId());
        p.put("kind", job.kind().name());
        p.put("requestedBy", job.requestedBy());
        p.put("channel", job.channel().name());
        p.put("params", job.params());
        p.put("requestedAt", Timestamp.from(job.requestedAt()));
        update("""
                INSERT INTO async_job (tenant_id, job_id, kind, status, requested_by, channel, params, requested_at)
                VALUES (:tenantId, :jobId, :kind, 'QUEUED', :requestedBy, :channel, CAST(:params AS jsonb), :requestedAt)
                """, p);
    }

    @Override
    public List<JobRecord> active(JobKind lockKind) {
        return query("SELECT " + COLUMNS + """
                 FROM async_job
                WHERE tenant_id = :tenantId
                  AND status IN ('QUEUED', 'RUNNING')
                  AND (CASE kind WHEN 'DESTROY_DRY_RUN' THEN 'DESTROY' ELSE kind END) = :lockKind
                ORDER BY requested_at, job_id
                """, Map.of("lockKind", lockKind.lockKind().name()), MAPPER);
    }

    @Override
    public boolean markRunning(UUID jobId, Instant startedAt) {
        return update("""
                UPDATE async_job SET status = 'RUNNING', started_at = GREATEST(:at, requested_at)
                 WHERE tenant_id = :tenantId AND job_id = :jobId AND status = 'QUEUED'
                """, Map.of("at", Timestamp.from(startedAt), "jobId", jobId)) == 1;
    }

    @Override
    public boolean succeed(UUID jobId, Instant finishedAt, String resultRef, String reportSha256, byte[] reportKeyWrapped, String reportKekId) {
        return update("""
                UPDATE async_job
                   SET status = 'SUCCEEDED', finished_at = GREATEST(:at, started_at), result_ref = :ref, report_sha256 = :sha, report_key_wrapped = :wrapped,
                       report_kek_id = :kek
                 WHERE tenant_id = :tenantId AND job_id = :jobId AND status = 'RUNNING'
                """, Map.of("at", Timestamp.from(finishedAt), "ref", resultRef, "sha", reportSha256, "wrapped", reportKeyWrapped, "kek", reportKekId,
                "jobId", jobId)) == 1;
    }

    @Override
    public boolean fail(UUID jobId, JobRecord.Status from, Instant finishedAt, JobError error) {
        if (!from.active()) {
            throw new IllegalArgumentException("only an active job can fail: " + from);
        }
        return update("""
                UPDATE async_job SET status = 'FAILED', finished_at = GREATEST(:at, coalesce(started_at, requested_at)), error_code = :code
                 WHERE tenant_id = :tenantId AND job_id = :jobId AND status = :from
                """, Map.of("at", Timestamp.from(finishedAt), "code", error.name(), "jobId", jobId, "from", from.name())) == 1;
    }

    @Override
    public Optional<JobRecord> find(UUID jobId) {
        return queryAtMostOne("SELECT " + COLUMNS + " FROM async_job WHERE tenant_id = :tenantId AND job_id = :jobId", Map.of("jobId", jobId), MAPPER);
    }

    @Override
    public Optional<ReportKey> reportKey(UUID jobId) {
        return queryAtMostOne("""
                SELECT report_kek_id, report_key_wrapped
                  FROM async_job
                 WHERE tenant_id = :tenantId AND job_id = :jobId AND status = 'SUCCEEDED'
                """, Map.of("jobId", jobId), (rs, n) -> new ReportKey(rs.getString("report_kek_id"), rs.getBytes("report_key_wrapped")));
    }

    @Override
    public List<JobRecord> recent(int limit, Optional<Position> before) {
        if (before.isEmpty()) {
            return query("SELECT " + COLUMNS + """
                     FROM async_job
                    WHERE tenant_id = :tenantId
                    ORDER BY requested_at DESC, job_id DESC
                    LIMIT :limit
                    """, Map.of("limit", limit), MAPPER);
        }
        return query("SELECT " + COLUMNS + """
                 FROM async_job
                WHERE tenant_id = :tenantId
                  AND (requested_at, job_id) < (:at, :jobId)
                ORDER BY requested_at DESC, job_id DESC
                LIMIT :limit
                """, Map.of("limit", limit, "at", Timestamp.from(before.get().requestedAt()), "jobId", before.get().jobId()), MAPPER);
    }
}
