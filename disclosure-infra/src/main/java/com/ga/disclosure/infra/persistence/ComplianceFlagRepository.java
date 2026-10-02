package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPayloads;
import com.ga.disclosure.audit.outbox.OutboxPort;
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

/**
 * 준법 플래그 저장소(설계서 §5 {@code compliance_flag}). 플래그가 <b>새로 열리면</b> 같은 트랜잭션에서 아웃박스 {@code ComplianceFlagRaised}를
 * 적재한다(4 계획 승인 Q13) — 워크플로·준법 배치 어느 경로로 열려도 이 한 곳을 지나므로 누락이 없다. 이미 열린 플래그를 다시 돌려받는 경우는
 * 새 이벤트가 아니다. payload의 설계사는 확인서 플래그일 때 그 확인서의 {@code agent_id}.
 */
@Repository
public class ComplianceFlagRepository extends TenantScopedRepository implements ComplianceFlagPort, DisclosureFlagPort {

    private final OutboxPort outbox;

    public ComplianceFlagRepository(TenantJdbcGateway gateway, OutboxPort outbox) {
        super(gateway);
        this.outbox = java.util.Objects.requireNonNull(outbox, "outbox");
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
            UUID flagId = created.getFirst();
            String agentId = disclosureIdOrNull == null ? null : queryAtMostOne("""
                    SELECT agent_id
                      FROM disclosure
                     WHERE tenant_id = :tenantId
                       AND disclosure_id = :disclosureId
                    """, Map.of("disclosureId", disclosureIdOrNull), (rs, n) -> rs.getString("agent_id")).orElse(null);
            outbox.append(EventType.ComplianceFlagRaised, flagId.toString(), raisedAt,
                    OutboxPayloads.complianceFlagRaised(flagId, type, severity, disclosureIdOrNull, null, agentId, raisedAt));
            return new ComplianceFlagPort.RaisedFlag(flagId, true);
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

    /** 워크플로 유형의 열린 플래그만(준법 배치가 확인서에 단 다른 유형은 이 포트의 몫이 아니다). */
    @Override
    public List<DisclosureFlagPort.OpenFlag> openFor(DisclosureId disclosureId) {
        return query("""
                SELECT flag_id, type, target_kind, target_id
                  FROM compliance_flag
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :disclosureId
                   AND resolved_at IS NULL
                   AND type = ANY(:types)
                 ORDER BY raised_at, flag_id
                """, Map.of("disclosureId", disclosureId.value(), "types",
                java.util.Arrays.stream(DisclosureFlagPort.Type.values()).map(Enum::name).toArray(String[]::new)),
                (rs, n) -> new DisclosureFlagPort.OpenFlag(rs.getObject("flag_id", UUID.class),
                DisclosureFlagPort.Type.valueOf(rs.getString("type")), rs.getString("target_kind"), rs.getString("target_id")));
    }

    @Override
    public List<DisclosureFlagPort.FlagSummary> allFor(DisclosureId disclosureId) {
        return query("""
                SELECT flag_id, type, resolved_at IS NULL AS open
                  FROM compliance_flag
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :disclosureId
                 ORDER BY raised_at, flag_id
                """, Map.of("disclosureId", disclosureId.value()),
                (rs, n) -> new DisclosureFlagPort.FlagSummary(rs.getObject("flag_id", UUID.class), rs.getString("type"), rs.getBoolean("open")));
    }

    @Override
    public boolean resolve(UUID flagId, DisclosureFlagPort.Resolution resolution, String resolvedBy, Instant at) {
        return update("""
                UPDATE compliance_flag
                   SET resolved_at = :at, resolved_by = :by, resolution = :resolution
                 WHERE tenant_id = :tenantId
                   AND flag_id = :flagId
                   AND resolved_at IS NULL
                """, Map.of("flagId", flagId, "at", Timestamp.from(at), "by", resolvedBy, "resolution", resolution.name())) == 1;
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
