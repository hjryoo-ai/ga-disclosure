package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.customer.CustomerRegistrationLimitStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * {@link CustomerRegistrationLimitStore} 어댑터(6B 계획 §9.6): 주체별 트랜잭션 advisory 잠금 → 그 주체의 {@code since} 뒤 감사 {@code CUSTOMER_REGISTER}
 * 행 수. 같은 주체의 동시 등록은 잠금에서 줄을 서고, 앞 요청이 커밋한 감사 행을 뒤 요청의 집계가 본다(READ COMMITTED — 문장마다 새 스냅샷).
 */
@Repository
public class CustomerRegistrationLimitRepository extends TenantScopedRepository implements CustomerRegistrationLimitStore {

    public CustomerRegistrationLimitRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public int lockAndCountSince(String subject, Instant since) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(since, "since");
        query("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended('ga.customer-register|' || :tenantId || '|' || :subject, 0))) l",
                Map.of("subject", subject), (rs, n) -> 1);
        return query("""
                SELECT count(*) AS n FROM audit_log
                 WHERE tenant_id = :tenantId AND action = 'CUSTOMER_REGISTER' AND actor_subject = :subject AND at > :since
                """, Map.of("subject", subject, "since", Timestamp.from(since)), (rs, n) -> rs.getInt("n")).getFirst();
    }
}
