package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.disclosure.rules.version.RuleVersionPort;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 룰 버전 저장소. 해석기용 조회 포트({@link RuleVersionPort})와 룰 거버넌스(배포·승인·활성화·대사)가 쓰는 변경을 구현한다.
 * 불변 규칙은 DB 트리거(V4)가 강제한다 — 이 클래스에는 GLOBAL body를 바꾸는 메서드 자체가 없다. 겹침은 DB 배타 제약이 막는다.
 */
@Repository
public class RuleVersionRepository extends TenantScopedRepository implements RuleVersionPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final RowMapper<RuleVersion> MAPPER = (rs, rowNum) -> {
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        return new RuleVersion(
                RuleVersionId.of(rs.getString("rule_version_id")),
                RuleScope.valueOf(rs.getString("scope")),
                rs.getObject("apply_from", LocalDate.class),
                rs.getObject("apply_to", LocalDate.class),
                RuleStatus.valueOf(rs.getString("status")),
                rs.getString("approved_by"),
                approvedAt == null ? null : approvedAt.toInstant(),
                JSON.readTree(rs.getString("body")),
                rs.getString("source_bundle_id"),
                rs.getString("bundle_hash"));
    };

    public RuleVersionRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    /** 삽입. GLOBAL은 APPROVED(번들 복제본), TENANT는 DRAFT로만 들어간다(V4 GD040). */
    public void insert(RuleVersion rule) {
        Map<String, Object> params = new HashMap<>();
        params.put("ruleVersionId", rule.id().value());
        params.put("scope", rule.scope().name());
        params.put("applyFrom", rule.applyFrom());
        params.put("applyTo", rule.applyTo());
        params.put("status", rule.status().name());
        params.put("approvedBy", rule.approvedBy());
        params.put("approvedAt", timestamp(rule.approvedAt()));
        params.put("body", JSON.writeValueAsString(rule.body()));
        params.put("sourceBundleId", rule.sourceBundleId());
        params.put("bundleHash", rule.bundleHash());
        update("""
                INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, apply_to, status,
                                          approved_by, approved_at, body, source_bundle_id, bundle_hash)
                VALUES (:tenantId, :ruleVersionId, :scope, :applyFrom, :applyTo, :status,
                        :approvedBy, :approvedAt, CAST(:body AS jsonb), :sourceBundleId, :bundleHash)
                """, params);
    }

    public Optional<RuleVersion> find(RuleVersionId id) {
        return queryAtMostOne("""
                SELECT rule_version_id, scope, apply_from, apply_to, status, approved_by, approved_at, body::text AS body,
                       source_bundle_id, bundle_hash
                  FROM rule_version
                 WHERE tenant_id = :tenantId
                   AND rule_version_id = :ruleVersionId
                """, Map.of("ruleVersionId", id.value()), MAPPER);
    }

    /** {@inheritDoc} 호출 테넌트는 바인딩된 테넌트와 같아야 한다. */
    @Override
    public List<RuleVersion> findActive(TenantId tenant, RuleScope scope, LocalDate asOf) {
        requireBound(tenant);
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(asOf, "asOf");
        return query("""
                SELECT rule_version_id, scope, apply_from, apply_to, status, approved_by, approved_at, body::text AS body,
                       source_bundle_id, bundle_hash
                  FROM rule_version
                 WHERE tenant_id = :tenantId
                   AND scope = :scope
                   AND status IN ('ACTIVE', 'RETIRED')
                   AND apply_from <= :asOf
                   AND (apply_to IS NULL OR apply_to > :asOf)
                 ORDER BY rule_version_id
                """, Map.of("scope", scope.name(), "asOf", asOf), MAPPER);
    }

    public List<RuleVersion> findByStatus(RuleStatus status) {
        return query("""
                SELECT rule_version_id, scope, apply_from, apply_to, status, approved_by, approved_at, body::text AS body,
                       source_bundle_id, bundle_hash
                  FROM rule_version
                 WHERE tenant_id = :tenantId
                   AND status = :status
                 ORDER BY apply_from, rule_version_id
                """, Map.of("status", status.name()), MAPPER);
    }

    /** 번들 대사 대상: 이 테넌트의 GLOBAL 복제본 전부(상태 무관). */
    public List<RuleVersion> findGlobalReplicas() {
        return query("""
                SELECT rule_version_id, scope, apply_from, apply_to, status, approved_by, approved_at, body::text AS body,
                       source_bundle_id, bundle_hash
                  FROM rule_version
                 WHERE tenant_id = :tenantId
                   AND scope = 'GLOBAL'
                 ORDER BY apply_from, rule_version_id
                """, Map.of(), MAPPER);
    }

    /** 선행 룰 닫기: {@code apply_to}가 NULL일 때만 쓴다(V4 GD042가 재기록을 막는다). 갱신 행 수를 돌려준다. */
    public int closeApplyTo(RuleVersionId id, LocalDate applyTo) {
        return update("""
                UPDATE rule_version
                   SET apply_to = :applyTo
                 WHERE tenant_id = :tenantId
                   AND rule_version_id = :ruleVersionId
                   AND apply_to IS NULL
                """, Map.of("applyTo", applyTo, "ruleVersionId", id.value()));
    }

    /** DRAFT → APPROVED(승인 기록 동시 기록). 갱신 행 수를 돌려준다(0이면 DRAFT가 아니었음). */
    public int approve(RuleVersionId id, String approvedBy, Instant approvedAt) {
        return update("""
                UPDATE rule_version
                   SET status = 'APPROVED', approved_by = :approvedBy, approved_at = :approvedAt
                 WHERE tenant_id = :tenantId
                   AND rule_version_id = :ruleVersionId
                   AND status = 'DRAFT'
                """, Map.of("approvedBy", approvedBy, "approvedAt", timestamp(approvedAt), "ruleVersionId", id.value()));
    }

    /** {@code from} 상태일 때만 {@code to}로 전이한다(한 단계 전진만 허용 — V4 GD043). 갱신 행 수를 돌려준다. */
    public int transition(RuleVersionId id, RuleStatus from, RuleStatus to) {
        return update("""
                UPDATE rule_version
                   SET status = :to
                 WHERE tenant_id = :tenantId
                   AND rule_version_id = :ruleVersionId
                   AND status = :from
                """, Map.of("to", to.name(), "from", from.name(), "ruleVersionId", id.value()));
    }

    private static void requireBound(TenantId tenant) {
        if (!TenantContext.current().equals(tenant)) {
            throw new IllegalArgumentException("port called for " + tenant + " while bound to " + TenantContext.current());
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
