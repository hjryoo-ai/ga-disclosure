package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.flag.VerifyEvidencePort;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code CHAIN_BROKEN} 해소 근거(6B 계획 §7): 작업 행과 {@code VERIFY_RUN} 감사 행만 읽는다 — 보고서를 열지 않는다. 바인딩된 테넌트의 RLS 아래에서
 * 읽으므로 다른 테넌트의 작업은 "없음"이다.
 */
@Repository
public class VerifyEvidenceRepository extends TenantScopedRepository implements VerifyEvidencePort {

    public VerifyEvidenceRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public Optional<Job> job(UUID jobId) {
        return queryAtMostOne("""
                SELECT kind, status, started_at, report_sha256
                  FROM async_job
                 WHERE tenant_id = :tenantId
                   AND job_id = :jobId
                """, Map.of("jobId", jobId), (rs, n) -> new Job(rs.getString("kind"), rs.getString("status"),
                Optional.ofNullable(rs.getTimestamp("started_at")).map(java.sql.Timestamp::toInstant), Optional.ofNullable(rs.getString("report_sha256"))));
    }

    /** 같은 보고서 해시의 VERIFY_RUN 행이 여럿이면(같은 보고서를 다시 낸 실행) 결과가 하나로 같아야 한다 — 다르면 빈 값(판정 불가 = 거부). */
    @Override
    public Optional<String> verifyResult(String reportSha256) {
        List<String> results = query("""
                SELECT DISTINCT detail ->> 'result' AS result
                  FROM audit_log
                 WHERE tenant_id = :tenantId
                   AND action = 'VERIFY_RUN'
                   AND detail ->> 'reportSha256' = :sha
                """, Map.of("sha", reportSha256), (rs, n) -> rs.getString("result"));
        return results.size() == 1 ? Optional.ofNullable(results.getFirst()) : Optional.empty();
    }
}
