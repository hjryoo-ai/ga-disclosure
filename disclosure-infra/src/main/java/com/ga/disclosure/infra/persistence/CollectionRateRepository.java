package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.metric.CollectionRates;
import com.ga.disclosure.workflow.authz.ListScope;
import com.ga.disclosure.workflow.rate.CollectionRateStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * {@link CollectionRateStore} 어댑터(V14 {@code collection_rate_snapshot}, V19 활성 연결 = {@code superseded_by}·{@code carried_to} 모두 NULL). 입력은 활성
 * 연결과 그 확인서의 사실이고 파기·폐기 제외는 산식이 한다. 스냅샷은 INSERT만(GD135 append-only, 유일 (달, 조직, 룰 버전)).
 */
@Repository
public class CollectionRateRepository extends TenantScopedRepository implements CollectionRateStore {

    public CollectionRateRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public List<CollectionRates.Linked> linked(LocalDate from, LocalDate toExclusive) {
        return query("""
                SELECT d.disclosure_no, d.org_path, l.contract_date, d.status, d.completed_at, d.destroyed_at IS NOT NULL AS destroyed
                  FROM contract_link l
                  JOIN disclosure d ON d.tenant_id = l.tenant_id AND d.tenant_id = :tenantId AND d.disclosure_id = l.disclosure_id
                 WHERE l.tenant_id = :tenantId
                   AND l.superseded_by IS NULL
                   AND l.carried_to IS NULL
                   AND l.contract_date >= :from
                   AND l.contract_date < :to
                 ORDER BY l.contract_date, l.link_id
                """, Map.of("from", Date.valueOf(from), "to", Date.valueOf(toExclusive)), (rs, n) -> {
            Timestamp completed = rs.getTimestamp("completed_at");
            return new CollectionRates.Linked(Optional.ofNullable(rs.getString("disclosure_no")), rs.getString("org_path"),
                    rs.getDate("contract_date").toLocalDate(), DisclosureStatus.valueOf(rs.getString("status")),
                    Optional.ofNullable(completed).map(Timestamp::toInstant), rs.getBoolean("destroyed"));
        });
    }

    @Override
    public List<String> unmatchedPolicyKeys(LocalDate from, LocalDate toExclusive) {
        return query("""
                SELECT DISTINCT encode(sha256(convert_to(u.policy_no, 'UTF8')), 'hex') AS policy_key
                  FROM contract_link_unmatched u
                 WHERE u.tenant_id = :tenantId
                   AND u.reason = 'UNMATCHED'
                   AND u.contract_date >= :from
                   AND u.contract_date < :to
                   AND NOT EXISTS (SELECT 1
                                     FROM contract_link l
                                    WHERE l.tenant_id = :tenantId
                                      AND l.policy_no = u.policy_no
                                      AND l.superseded_by IS NULL
                                      AND l.carried_to IS NULL)
                 ORDER BY policy_key
                """, Map.of("from", Date.valueOf(from), "to", Date.valueOf(toExclusive)), (rs, n) -> rs.getString("policy_key"));
    }

    @Override
    public boolean exists(LocalDate periodMonth, RuleVersionId ruleVersion) {
        return queryAtMostOne("""
                SELECT EXISTS (SELECT 1
                                 FROM collection_rate_snapshot
                                WHERE tenant_id = :tenantId
                                  AND period_month = :month
                                  AND rule_version_id = :rule) AS found
                """, Map.of("month", Date.valueOf(periodMonth), "rule", ruleVersion.value()), (rs, n) -> rs.getBoolean("found")).orElse(false);
    }

    @Override
    public void insert(Row row) {
        Map<String, Object> p = new HashMap<>();
        p.put("snapshotId", row.snapshotId());
        p.put("month", Date.valueOf(row.periodMonth()));
        p.put("org", row.orgPath());
        p.put("formula", row.formula());
        p.put("denominator", row.denominator());
        p.put("numerator", row.numerator());
        p.put("rateBp", row.rateBp().isPresent() ? row.rateBp().getAsInt() : null);
        p.put("at", Timestamp.from(row.computedAt()));
        p.put("rule", row.ruleVersionId().value());
        p.put("hash", row.inputsHash());
        p.put("jobId", row.jobId());
        update("""
                INSERT INTO collection_rate_snapshot (tenant_id, snapshot_id, period_month, org_path, formula, denominator, numerator, rate_bp,
                                                      computed_at, rule_version_id, inputs_hash, job_id)
                VALUES (:tenantId, :snapshotId, :month, :org, :formula, :denominator, :numerator, CAST(:rateBp AS integer),
                        :at, :rule, :hash, :jobId)
                """, p);
    }

    /** 범위는 스냅샷의 조직 경로로 건다(세그먼트 접두, {@code starts_with}라 LIKE 와일드카드가 없다). 조직 범위에는 테넌트 전체 행이 없다. */
    @Override
    public List<Row> list(ListScope scope, LocalDate fromMonth, LocalDate toMonth, Optional<String> orgPath) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder where = new StringBuilder();
        switch (scope) {
            case ListScope.WholeTenant w -> {
            }
            case ListScope.UnderOrg u -> {
                where.append(" AND org_path <> '/' AND (org_path = :org OR starts_with(org_path, :org || '/'))");
                p.put("org", u.org().value());
            }
            case ListScope.OwnedBy o -> {
                return List.of();                                            // 징구율에는 설계사 칸이 없다
            }
        }
        orgPath.ifPresent(o -> {
            where.append(" AND org_path = :orgPath");
            p.put("orgPath", o);
        });
        p.put("from", Date.valueOf(fromMonth));
        p.put("to", Date.valueOf(toMonth));
        return query("""
                SELECT snapshot_id, period_month, org_path, formula, denominator, numerator, rate_bp, computed_at, rule_version_id, inputs_hash, job_id
                  FROM collection_rate_snapshot
                 WHERE tenant_id = :tenantId
                   AND period_month >= :from
                   AND period_month <= :to""" + where + """

                 ORDER BY period_month, org_path, rule_version_id
                """, p, (rs, n) -> {
            int rate = rs.getInt("rate_bp");
            OptionalInt rateBp = rs.wasNull() ? OptionalInt.empty() : OptionalInt.of(rate);
            return new Row(rs.getObject("snapshot_id", UUID.class), rs.getDate("period_month").toLocalDate(), rs.getString("org_path"), rs.getString("formula"),
                    rs.getInt("denominator"), rs.getInt("numerator"), rateBp, rs.getTimestamp("computed_at").toInstant(),
                    RuleVersionId.of(rs.getString("rule_version_id")), rs.getString("inputs_hash"), rs.getObject("job_id", UUID.class));
        });
    }
}
