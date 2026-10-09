package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.disclosure.RetentionRecomputeStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RetentionRecomputeStore} 어댑터(6B 계획 §8). 대상은 봉인 이후(번호·보존기한 있음)·미파기 확인서. 쓰기는 {@link DisclosureRepository#extendRetention}
 * 하나(더 늦을 때만·미파기일 때만) — 이 클래스에는 UPDATE 문이 없다.
 */
@Repository
public class RetentionRecomputeRepository extends TenantScopedRepository implements RetentionRecomputeStore {

    private final DisclosureRepository disclosures;

    public RetentionRecomputeRepository(TenantJdbcGateway gateway, DisclosureRepository disclosures) {
        super(gateway);
        this.disclosures = Objects.requireNonNull(disclosures, "disclosures");
    }

    @Override
    public List<Target> live(Optional<DisclosureId> after, int limit) {
        Map<String, Object> p = new HashMap<>();
        p.put("limit", limit);
        String from = "";
        if (after.isPresent()) {
            from = " AND disclosure_id > :after";
            p.put("after", after.get().value());
        }
        return query("""
                SELECT disclosure_id, disclosure_no, retention_until, sealed_at, completed_at, contract_date
                  FROM disclosure
                 WHERE tenant_id = :tenantId
                   AND disclosure_no IS NOT NULL
                   AND retention_until IS NOT NULL
                   AND destroyed_at IS NULL""" + from + """

                 ORDER BY disclosure_id
                 LIMIT :limit
                """, p, (rs, n) -> {
            Timestamp completed = rs.getTimestamp("completed_at");
            Date contract = rs.getDate("contract_date");
            return new Target(DisclosureId.of(rs.getObject("disclosure_id", UUID.class)), rs.getString("disclosure_no"),
                    rs.getDate("retention_until").toLocalDate(), rs.getTimestamp("sealed_at").toInstant(),
                    Optional.ofNullable(completed).map(Timestamp::toInstant), Optional.ofNullable(contract).map(Date::toLocalDate));
        });
    }

    @Override
    public int destroyed() {
        return queryAtMostOne("""
                SELECT count(*) AS n FROM disclosure WHERE tenant_id = :tenantId AND disclosure_no IS NOT NULL AND destroyed_at IS NOT NULL
                """, Map.of(), (rs, n) -> rs.getInt("n")).orElse(0);
    }

    @Override
    public boolean extendRetention(DisclosureId id, LocalDate until) {
        return disclosures.extendRetention(id, until);
    }
}
