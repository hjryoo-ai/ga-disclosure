package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 룰 버전 저장소. 룰 해석기(기준일 해석·Ambiguous 판정의 정본)는 Phase 1이므로 여기서는 단순 조회와
 * "2건 이상이면 예외"({@link com.ga.platform.spring.jdbc.AmbiguousResultException})까지만 한다.
 * 겹치는 ACTIVE는 DB 배타 제약({@code ex_rule_version_active_overlap})이 먼저 막는다.
 */
@Repository
public class RuleVersionRepository extends TenantScopedRepository {

    private static final RowMapper<RuleVersionRecord> MAPPER = (rs, rowNum) -> {
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        return new RuleVersionRecord(
                RuleVersionId.of(rs.getString("rule_version_id")),
                rs.getString("scope"),
                rs.getObject("apply_from", LocalDate.class),
                rs.getObject("apply_to", LocalDate.class),
                RuleStatus.valueOf(rs.getString("status")),
                rs.getString("approved_by"),
                approvedAt == null ? null : approvedAt.toInstant(),
                rs.getString("body"));
    };

    public RuleVersionRepository(Gateway gateway) {
        super(gateway);
    }

    public void insert(RuleVersionRecord rule) {
        Map<String, Object> params = new HashMap<>();
        params.put("ruleVersionId", rule.ruleVersionId().value());
        params.put("scope", rule.scope());
        params.put("applyFrom", rule.applyFrom());
        params.put("applyTo", rule.applyTo());
        params.put("status", rule.status().name());
        params.put("approvedBy", rule.approvedBy());
        params.put("approvedAt", rule.approvedAt() == null ? null : Timestamp.from(rule.approvedAt()));
        params.put("body", rule.bodyJson());
        update("""
                INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, apply_to, status,
                                          approved_by, approved_at, body)
                VALUES (:tenantId, :ruleVersionId, :scope, :applyFrom, :applyTo, :status,
                        :approvedBy, :approvedAt, CAST(:body AS jsonb))
                """, params);
    }

    /** 상태 변경(예: APPROVED → ACTIVE). 겹침이 생기면 DB 배타 제약이 거부한다. */
    public int updateStatus(RuleVersionId ruleVersionId, RuleStatus status) {
        return update("""
                UPDATE rule_version
                   SET status = :status
                 WHERE tenant_id = :tenantId
                   AND rule_version_id = :ruleVersionId
                """, Map.of("status", status.name(), "ruleVersionId", ruleVersionId.value()));
    }

    /** {@code date}에 적용되는 ACTIVE 룰(0건이면 빈 값, 2건 이상이면 AmbiguousResultException). */
    public Optional<RuleVersionRecord> findActiveOn(String scope, LocalDate date) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(date, "date");
        return queryAtMostOne("""
                SELECT rule_version_id, scope, apply_from, apply_to, status, approved_by, approved_at, body::text AS body
                  FROM rule_version
                 WHERE tenant_id = :tenantId
                   AND scope = :scope
                   AND status = 'ACTIVE'
                   AND apply_from <= :date
                   AND (apply_to IS NULL OR apply_to > :date)
                """, Map.of("scope", scope, "date", date), MAPPER);
    }
}
