package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPayloads;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.compliance.rules.ComplianceFlagPort;
import com.ga.disclosure.workflow.authz.ListScope;
import com.ga.disclosure.workflow.flag.FlagCommandPort;
import com.ga.disclosure.workflow.flag.FlagLookup;
import com.ga.disclosure.workflow.flag.FlagPolicy;
import com.ga.disclosure.workflow.flag.FlagPolicySource;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 준법 플래그 저장소(설계서 §5 {@code compliance_flag}). 플래그가 <b>새로 열리면</b> 같은 트랜잭션에서 아웃박스 {@code ComplianceFlagRaised}를
 * 적재한다(4 계획 승인 Q13) — 워크플로·준법 배치 어느 경로로 열려도 이 한 곳을 지나므로 누락이 없다. 이미 열린 플래그를 다시 돌려받는 경우는
 * 새 이벤트가 아니다. payload의 설계사는 확인서 플래그일 때 그 확인서의 {@code agent_id}. 조회({@link FlagLookup})는 대상 확인서의 범위 사실과 번호만
 * 함께 읽는다.
 */
@Repository
public class ComplianceFlagRepository extends TenantScopedRepository implements ComplianceFlagPort, DisclosureFlagPort, FlagLookup, FlagCommandPort {

    private final OutboxPort outbox;
    private final FlagPolicySource policies;

    public ComplianceFlagRepository(TenantJdbcGateway gateway, OutboxPort outbox, FlagPolicySource policies) {
        super(gateway);
        this.outbox = java.util.Objects.requireNonNull(outbox, "outbox");
        this.policies = java.util.Objects.requireNonNull(policies, "policies");
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

    @Override
    public DisclosureFlagPort.RaisedFlag raiseUnattached(DisclosureFlagPort.Type type, String severity, String targetKind, String targetId,
                                                         Instant raisedAt) {
        ComplianceFlagPort.RaisedFlag f = raiseFor(type.name(), severity, null, targetKind, targetId, raisedAt);
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
        // 6B: 담당·설계사 가시성·기한은 열린 시각의 룰에서 복사해 고정한다(V14 GD134). 재사용되는 열린 플래그는 그때의 값을 그대로 둔다.
        FlagPolicy policy = policies.at(TenantContext.current(), type, raisedAt);
        params.put("assignedRole", policy.assignedRole().name());
        params.put("visibleToAgent", policy.visibleToAgent());
        params.put("dueAt", policy.dueAt().map(Timestamp::from).orElse(null));
        // 같은 유형·대상의 열린 플래그가 있으면(부분 유일 인덱스 ux_compliance_flag_open_target) 새 행을 만들지 않는다.
        List<UUID> created = query("""
                INSERT INTO compliance_flag (tenant_id, flag_id, type, disclosure_id, severity, raised_at, target_kind, target_id,
                                             assigned_role, visible_to_agent, due_at)
                VALUES (:tenantId, :flagId, :type, :disclosureId, :severity, :raisedAt, :targetKind, :targetId,
                        :assignedRole, :visibleToAgent, :dueAt)
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

    private static final String LISTED = """
            SELECT f.flag_id, f.type, f.resolved_at IS NULL AS open, f.raised_at, f.disclosure_id, d.disclosure_no
              FROM compliance_flag f
              LEFT JOIN disclosure d ON d.tenant_id = f.tenant_id AND d.tenant_id = :tenantId AND d.disclosure_id = f.disclosure_id
             WHERE f.tenant_id = :tenantId""";

    private static FlagLookup.Listed listed(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID disclosureId = rs.getObject("disclosure_id", UUID.class);
        return new FlagLookup.Listed(rs.getObject("flag_id", UUID.class), rs.getString("type"),
                rs.getBoolean("open") ? FlagLookup.FlagStatus.OPEN : FlagLookup.FlagStatus.RESOLVED, rs.getTimestamp("raised_at").toInstant(),
                Optional.ofNullable(disclosureId).map(DisclosureId::of), Optional.ofNullable(rs.getString("disclosure_no")));
    }

    @Override
    public List<FlagLookup.Listed> forDisclosure(DisclosureId disclosureId) {
        return query(LISTED + """

                   AND f.disclosure_id = :disclosureId
                 ORDER BY f.raised_at, f.flag_id
                """, Map.of("disclosureId", disclosureId.value()), (rs, n) -> listed(rs));
    }

    /** 범위는 대상 확인서의 사실(작성 설계사·작성 시점 조직 — 세그먼트 접두, {@code starts_with}라 LIKE 와일드카드가 없다)로 건다. */
    @Override
    public List<FlagLookup.Listed> page(ListScope scope, FlagLookup.Filter filter, Optional<FlagLookup.Position> after, int limit) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder where = new StringBuilder();
        switch (scope) {
            case ListScope.WholeTenant w -> {
            }
            case ListScope.OwnedBy o -> {
                where.append(" AND d.agent_id = :agentId");
                p.put("agentId", o.agent().value());
            }
            case ListScope.UnderOrg u -> {
                where.append(" AND d.org_path IS NOT NULL AND (d.org_path = :org OR starts_with(d.org_path, :org || '/'))");
                p.put("org", u.org().value());
            }
        }
        filter.status().ifPresent(st -> where.append(st == FlagLookup.FlagStatus.OPEN ? " AND f.resolved_at IS NULL" : " AND f.resolved_at IS NOT NULL"));
        filter.type().ifPresent(t -> {
            where.append(" AND f.type = :type");
            p.put("type", t);
        });
        filter.assignedRole().ifPresent(r -> {
            where.append(" AND f.assigned_role = :assignedRole");
            p.put("assignedRole", r);
        });
        filter.dueBefore().ifPresent(d -> {
            where.append(" AND f.due_at < :dueBefore");
            p.put("dueBefore", Timestamp.from(d));
        });
        after.ifPresent(a -> {
            where.append(" AND (f.raised_at, f.flag_id) < (:afterAt, :afterId)");
            p.put("afterAt", Timestamp.from(a.raisedAt()));
            p.put("afterId", a.flagId());
        });
        p.put("limit", limit);
        return query(LISTED + where + """

                 ORDER BY f.raised_at DESC, f.flag_id DESC
                 LIMIT :limit
                """, p, (rs, n) -> listed(rs));
    }

    @Override
    public Optional<FlagLookup.State> state(UUID flagId) {
        return queryAtMostOne("""
                SELECT flag_id, type, disclosure_id, raised_at, assigned_role, resolved_at IS NULL AS open
                  FROM compliance_flag
                 WHERE tenant_id = :tenantId
                   AND flag_id = :flagId
                """, Map.of("flagId", flagId), (rs, n) -> new FlagLookup.State(rs.getObject("flag_id", UUID.class), rs.getString("type"),
                Optional.ofNullable(rs.getObject("disclosure_id", UUID.class)).map(DisclosureId::of), rs.getTimestamp("raised_at").toInstant(),
                rs.getString("assigned_role"), rs.getBoolean("open")));
    }

    // ------------------------------------------------------------------ 6B 준법 큐 쓰기(FlagCommandPort, V14 GD134)

    @Override
    public boolean assign(UUID flagId, String assignee) {
        return update("""
                UPDATE compliance_flag
                   SET assignee = :assignee
                 WHERE tenant_id = :tenantId
                   AND flag_id = :flagId
                   AND resolved_at IS NULL
                """, Map.of("flagId", flagId, "assignee", assignee)) == 1;
    }

    @Override
    public boolean resolveManually(UUID flagId, String resolvedBy, Instant at, String resolutionCode, String evidenceJsonOrNull) {
        Map<String, Object> p = new HashMap<>();
        p.put("flagId", flagId);
        p.put("at", Timestamp.from(at));
        p.put("by", resolvedBy);
        p.put("resolution", FlagCommandPort.MANUAL_RESOLUTION);
        p.put("code", resolutionCode);
        p.put("evidence", evidenceJsonOrNull);
        return update("""
                UPDATE compliance_flag
                   SET resolved_at = :at, resolved_by = :by, resolution = :resolution, resolution_code = :code,
                       resolution_evidence = CAST(:evidence AS jsonb)
                 WHERE tenant_id = :tenantId
                   AND flag_id = :flagId
                   AND resolved_at IS NULL
                """, p) == 1;
    }

    @Override
    public List<FlagCommandPort.Breached> markSlaBreached(Instant now, int limit) {
        return query("""
                UPDATE compliance_flag f
                   SET sla_breached_at = :now
                  FROM (SELECT flag_id
                          FROM compliance_flag
                         WHERE tenant_id = :tenantId
                           AND resolved_at IS NULL
                           AND sla_breached_at IS NULL
                           AND due_at < :now
                         ORDER BY due_at, flag_id
                         LIMIT :limit
                           FOR UPDATE) due
                 WHERE f.tenant_id = :tenantId
                   AND f.flag_id = due.flag_id
                RETURNING f.flag_id, f.type, f.due_at
                """, Map.of("now", Timestamp.from(now), "limit", limit), (rs, n) -> new FlagCommandPort.Breached(rs.getObject("flag_id", UUID.class),
                rs.getString("type"), rs.getTimestamp("due_at").toInstant()));
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
