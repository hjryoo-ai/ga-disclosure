package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.compliance.rules.ComplianceFlagPort;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 준법 플래그 저장소(설계서 §5 {@code compliance_flag}). */
@Repository
public class ComplianceFlagRepository extends TenantScopedRepository implements ComplianceFlagPort {

    public ComplianceFlagRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public UUID raise(String type, String severity, Instant raisedAt) {
        UUID id = UUID.randomUUID();
        update("""
                INSERT INTO compliance_flag (tenant_id, flag_id, type, severity, raised_at)
                VALUES (:tenantId, :flagId, :type, :severity, :raisedAt)
                """, Map.of("flagId", id, "type", type, "severity", severity, "raisedAt", Timestamp.from(raisedAt)));
        return id;
    }

    /** 미해소 플래그 ID(유형별). */
    public List<UUID> findOpen(String type) {
        return query("""
                SELECT flag_id
                  FROM compliance_flag
                 WHERE tenant_id = :tenantId
                   AND type = :type
                   AND resolved_at IS NULL
                 ORDER BY raised_at, flag_id
                """, Map.of("type", type), (rs, n) -> rs.getObject("flag_id", UUID.class));
    }
}
