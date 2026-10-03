package com.ga.disclosure.infra.retention;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.workflow.retention.RetentionStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 파기 판정 재료(5 계획 §5.1·§5.3). 문장은 테이블마다 나눈다(테넌트 조건 스캔). */
@Repository
public class RetentionRepository extends TenantScopedRepository implements RetentionStore {

    public RetentionRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public List<DisclosureId> candidates(LocalDate today, int limit) {
        return query("""
                SELECT disclosure_id
                  FROM disclosure
                 WHERE tenant_id = :tenantId
                   AND disclosure_no IS NOT NULL
                   AND status IN ('COMPLETED', 'EXPIRED', 'VOID', 'SUPERSEDED')
                   AND destroyed_at IS NULL
                   AND retention_until < :today
                 ORDER BY retention_until, disclosure_id
                 LIMIT :limit
                """, Map.of("today", Date.valueOf(today), "limit", limit), (rs, n) -> DisclosureId.of(rs.getObject("disclosure_id", UUID.class)));
    }

    @Override
    public Optional<Candidate> candidate(DisclosureId id, LocalDate today) {
        record Row(DisclosureStatus status, String no, LocalDate consult, String rule, String tenantRule, LocalDate until, Instant sealed,
                   Instant completed, LocalDate contract, Instant destroyed, String customer) {
        }
        Optional<Row> row = queryAtMostOne("""
                SELECT status, disclosure_no, consult_date, rule_version_id, tenant_rule_version_id, retention_until, sealed_at, completed_at,
                       contract_date, destroyed_at, customer_ref
                  FROM disclosure
                 WHERE tenant_id = :tenantId AND disclosure_id = :id
                """, Map.of("id", id.value()), (rs, n) -> new Row(DisclosureStatus.valueOf(rs.getString("status")), rs.getString("disclosure_no"),
                rs.getDate("consult_date").toLocalDate(), rs.getString("rule_version_id"), rs.getString("tenant_rule_version_id"),
                date(rs.getDate("retention_until")), instant(rs.getTimestamp("sealed_at")), instant(rs.getTimestamp("completed_at")),
                date(rs.getDate("contract_date")), instant(rs.getTimestamp("destroyed_at")), rs.getString("customer_ref")));
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Row r = row.get();
        Map<String, Object> p = Map.of("id", id.value(), "customer", r.customer(), "today", Date.valueOf(today));
        boolean held = count("""
                SELECT count(*) FROM legal_hold
                 WHERE tenant_id = :tenantId AND released_at IS NULL AND (disclosure_id = :id OR customer_ref = :customer)
                """, p) > 0;
        long unlockedArtifacts = count("""
                SELECT count(*) FROM document_artifact
                 WHERE tenant_id = :tenantId AND disclosure_id = :id AND (retention_applied_until IS NULL OR retention_applied_until >= :today)
                """, p);
        long unlockedEvidence = count("""
                SELECT count(*) FROM signature_evidence
                 WHERE tenant_id = :tenantId AND disclosure_id = :id AND (retention_applied_until IS NULL OR retention_applied_until >= :today)
                """, p);
        boolean keyLive = count("SELECT count(*) FROM document_key WHERE tenant_id = :tenantId AND disclosure_id = :id AND wrapped_dek IS NOT NULL", p) > 0;
        return Optional.of(new Candidate(id, r.status(), r.no(), r.consult(), r.rule() == null ? null : RuleVersionId.of(r.rule()),
                r.tenantRule() == null ? null : RuleVersionId.of(r.tenantRule()), r.until(), r.sealed(), r.completed(), r.contract(), r.destroyed(),
                new CustomerRef(r.customer()), held, unlockedArtifacts == 0 && unlockedEvidence == 0, keyLive));
    }

    @Override
    public List<CustomerRef> customerCandidates(int limit) {
        return query("""
                SELECT customer_ref
                  FROM customer_ref
                 WHERE tenant_id = :tenantId AND destroyed_at IS NULL
                 ORDER BY created_at, customer_ref
                 LIMIT :limit
                """, Map.of("limit", limit), (rs, n) -> new CustomerRef(rs.getString("customer_ref")));
    }

    @Override
    public Optional<CustomerState> customer(CustomerRef ref) {
        record Row(Instant created, Instant destroyed) {
        }
        Optional<Row> row = queryAtMostOne("SELECT created_at, destroyed_at FROM customer_ref WHERE tenant_id = :tenantId AND customer_ref = :ref",
                Map.of("ref", ref.value()), (rs, n) -> new Row(instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("destroyed_at"))));
        if (row.isEmpty()) {
            return Optional.empty();
        }
        record Counts(int total, int live, Instant last) {
        }
        Counts c = query("""
                SELECT count(*) AS total, count(*) FILTER (WHERE destroyed_at IS NULL) AS live, max(destroyed_at) AS last_destroyed
                  FROM disclosure
                 WHERE tenant_id = :tenantId AND customer_ref = :ref
                """, Map.of("ref", ref.value()), (rs, n) -> new Counts(rs.getInt("total"), rs.getInt("live"), instant(rs.getTimestamp("last_destroyed"))))
                .getFirst();
        boolean held = count("SELECT count(*) FROM legal_hold WHERE tenant_id = :tenantId AND released_at IS NULL AND customer_ref = :ref",
                Map.of("ref", ref.value())) > 0;
        return Optional.of(new CustomerState(ref, row.get().created(), row.get().destroyed(), c.total(), c.live(), c.last(), held));
    }

    @Override
    public List<DisclosureId> sealedDisclosuresOf(CustomerRef ref) {
        return query("""
                SELECT disclosure_id
                  FROM disclosure
                 WHERE tenant_id = :tenantId AND customer_ref = :ref AND disclosure_no IS NOT NULL
                 ORDER BY chain_seq
                """, Map.of("ref", ref.value()), (rs, n) -> DisclosureId.of(rs.getObject("disclosure_id", UUID.class)));
    }

    private long count(String sql, Map<String, Object> params) {
        return query(sql, params, (rs, n) -> rs.getLong(1)).getFirst();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
