package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.disclosure.Review;
import com.ga.disclosure.workflow.disclosure.ReviewStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 관리자 예외 승인 저장소(V6 {@code review}, append-only — UPDATE·DELETE 없음, GD030·GD080). */
@Repository
public class ReviewRepository extends TenantScopedRepository implements ReviewStore {

    public ReviewRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public void append(Review r) {
        Map<String, Object> p = new HashMap<>();
        p.put("reviewId", r.reviewId());
        p.put("disclosureId", r.disclosureId().value());
        p.put("ruleId", r.ruleId());
        p.put("subjectHash", r.subjectHash());
        p.put("approvedBy", r.approvedBy());
        p.put("approvedRole", r.approvedRole());
        p.put("approvedAt", Timestamp.from(r.approvedAt()));
        p.put("reason", r.reason());
        update("""
                INSERT INTO review (tenant_id, review_id, disclosure_id, rule_id, subject_hash, approved_by, approved_role, approved_at, reason)
                VALUES (:tenantId, :reviewId, :disclosureId, :ruleId, :subjectHash, :approvedBy, :approvedRole, :approvedAt, :reason)
                """, p);
    }

    @Override
    public List<Review> findFor(DisclosureId disclosureId) {
        return query("""
                SELECT review_id, disclosure_id, rule_id, subject_hash, approved_by, approved_role, approved_at, reason
                  FROM review
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :disclosureId
                 ORDER BY approved_at, review_id
                """, Map.of("disclosureId", disclosureId.value()), (rs, n) -> new Review(rs.getObject("review_id", UUID.class),
                DisclosureId.of(rs.getObject("disclosure_id", UUID.class)), rs.getString("rule_id"), rs.getString("subject_hash"),
                rs.getString("approved_by"), rs.getString("approved_role"), rs.getTimestamp("approved_at").toInstant(), rs.getString("reason")));
    }
}
