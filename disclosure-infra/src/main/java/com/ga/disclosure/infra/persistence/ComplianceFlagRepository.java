package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.compliance.rules.ComplianceFlagPort;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 준법 플래그 저장소(설계서 §5 {@code compliance_flag}). */
@Repository
public class ComplianceFlagRepository extends TenantScopedRepository implements ComplianceFlagPort, DisclosureFlagPort {

    public ComplianceFlagRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public ComplianceFlagPort.RaisedFlag raiseOpen(String type, String severity, String targetKind, String targetId, Instant raisedAt) {
        return raiseFor(type, severity, null, targetKind, targetId, raisedAt);
    }

    /** 확인서 플래그(Phase 3A): {@code disclosure_id}를 함께 기록한다. 같은 유형·대상의 열린 플래그는 재사용. */
    @Override
    public DisclosureFlagPort.RaisedFlag raise(DisclosureFlagPort.Type type, String severity, DisclosureId disclosureId, String targetKind,
                                              String targetId, Instant raisedAt) {
        ComplianceFlagPort.RaisedFlag f = raiseFor(type.name(), severity, disclosureId.value(), targetKind, targetId, raisedAt);
        return new DisclosureFlagPort.RaisedFlag(f.flagId(), f.created());
    }

    private ComplianceFlagPort.RaisedFlag raiseFor(String type, String severity, UUID disclosureIdOrNull, String targetKind, String targetId, Instant raisedAt) {
        Map<String, Object> params = new HashMap<>();
        params.put("disclosureId", disclosureIdOrNull);
        params.put("flagId", UUID.randomUUID());
        params.put("type", type);
        params.put("severity", severity);
        params.put("targetKind", targetKind);
        params.put("targetId", targetId);
        params.put("raisedAt", Timestamp.from(raisedAt));
        // 같은 유형·대상의 열린 플래그가 있으면(부분 유일 인덱스 ux_compliance_flag_open_target) 새 행을 만들지 않는다.
        List<UUID> created = query("""
                INSERT INTO compliance_flag (tenant_id, flag_id, type, disclosure_id, severity, raised_at, target_kind, target_id)
                VALUES (:tenantId, :flagId, :type, :disclosureId, :severity, :raisedAt, :targetKind, :targetId)
                ON CONFLICT (tenant_id, type, target_kind, target_id) WHERE resolved_at IS NULL AND target_id IS NOT NULL
                DO NOTHING
                RETURNING flag_id
                """, params, (rs, n) -> rs.getObject("flag_id", UUID.class));
        if (!created.isEmpty()) {
            return new ComplianceFlagPort.RaisedFlag(created.getFirst(), true);
        }
        UUID open = queryAtMostOne("""
                SELECT flag_id
                  FROM compliance_flag
                 WHERE tenant_id = :tenantId
                   AND type = :type
                   AND target_kind = :targetKind
                   AND target_id = :targetId
                   AND resolved_at IS NULL
                """, params, (rs, n) -> rs.getObject("flag_id", UUID.class))
                .orElseThrow(() -> new IllegalStateException("flag insert skipped but no open " + type + " flag for " + targetKind + " " + targetId));
        return new ComplianceFlagPort.RaisedFlag(open, false);
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
