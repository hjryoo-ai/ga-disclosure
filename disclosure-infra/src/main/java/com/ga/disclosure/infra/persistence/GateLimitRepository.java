package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.gate.GateLimitStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * {@link GateLimitStore} 어댑터(Phase 8 — 6B 이월 ②): 주체별 트랜잭션 advisory 잠금 → 그 주체의 {@code since} 뒤 감사 {@code GATE_DECISION} 행 수.
 * 같은 주체의 동시 판정은 잠금에서 줄을 서고, 앞 요청이 커밋한 감사 행을 뒤 요청의 집계가 본다(READ COMMITTED). 집계는 V20 인덱스
 * {@code audit_log(tenant_id, actor_subject, action, at)}로 닿는다(V20AuditIndexIT).
 */
@Repository
public class GateLimitRepository extends TenantScopedRepository implements GateLimitStore {

    public GateLimitRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public int lockAndCountSince(String subject, Instant since) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(since, "since");
        query("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended('ga.gate|' || :tenantId || '|' || :subject, 0))) l",
                Map.of("subject", subject), (rs, n) -> 1);
        return query("""
                SELECT count(*) AS n FROM audit_log
                 WHERE tenant_id = :tenantId AND action = 'GATE_DECISION' AND actor_subject = :subject AND at > :since
                """, Map.of("subject", subject, "since", Timestamp.from(since)), (rs, n) -> rs.getInt("n")).getFirst();
    }
}
