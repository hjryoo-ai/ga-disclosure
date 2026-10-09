package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.disclosure.IdleDraftStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link IdleDraftStore} 어댑터. 마지막 변경 = 그 확인서를 대상으로 한 변경 감사 행의 가장 늦은 {@code at}(감사 행은 업무 트랜잭션과 같이 쓰인다).
 * 봉인 전 상태는 DB 함수 {@code ga_is_mutable_status}로 판정한다(가드와 같은 정의).
 */
@Repository
public class IdleDraftRepository extends TenantScopedRepository implements IdleDraftStore {

    public IdleDraftRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public List<Idle> idleSince(Instant changedBefore, Set<String> changeActions, int limit) {
        return query("""
                SELECT d.disclosure_id, max(a.at) AS last_changed_at
                  FROM disclosure d
                  JOIN audit_log a ON a.tenant_id = d.tenant_id AND a.target_kind = 'DISCLOSURE' AND a.target_id = d.disclosure_id::text
                 WHERE d.tenant_id = :tenantId
                   AND a.tenant_id = :tenantId
                   AND a.action IN (:actions)
                   AND ga_is_mutable_status(d.status)
                   AND d.destroyed_at IS NULL
                 GROUP BY d.disclosure_id
                HAVING max(a.at) < :before
                 ORDER BY max(a.at), d.disclosure_id
                 LIMIT :limit
                """, Map.of("actions", List.copyOf(changeActions), "before", Timestamp.from(changedBefore), "limit", limit),
                (rs, n) -> new Idle(DisclosureId.of(rs.getObject("disclosure_id", UUID.class)), rs.getTimestamp("last_changed_at").toInstant()));
    }

    @Override
    public Optional<Instant> lastChange(DisclosureId disclosure, Set<String> changeActions) {
        return queryAtMostOne("""
                SELECT max(at) AS last_changed_at
                  FROM audit_log
                 WHERE tenant_id = :tenantId AND target_kind = 'DISCLOSURE' AND target_id = :id AND action IN (:actions)
                """, Map.of("id", disclosure.toString(), "actions", List.copyOf(changeActions)),
                (rs, n) -> Optional.ofNullable(rs.getTimestamp("last_changed_at")).map(Timestamp::toInstant)).flatMap(o -> o);
    }
}
