package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditChain;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 감사 로그 append(해시체인). 테넌트별 append는 트랜잭션 범위 어드바이저리 락으로 직렬화한다 —
 * {@code pg_advisory_xact_lock(hashtextextended('audit_log:' || tenant, 0))}를 잡은 뒤 마지막 행을 읽고 다음 행을 쓴다.
 * 락은 커밋·롤백과 함께 풀리고, 해시 충돌로 두 테넌트가 같은 키를 받아도 직렬화가 늘 뿐 정확성은 같다.
 * 최후 방어는 PK {@code (tenant_id, seq)}다. append-only는 V3 트리거가 강제한다.
 */
@Repository
public class AuditLogRepository extends TenantScopedRepository implements AuditPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Head(long seq, String entryHash) {
    }

    public AuditLogRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public AuditRecord append(AuditEntry entry) {
        TenantId tenant = TenantContext.current();
        query("""
                SELECT pg_advisory_xact_lock(hashtextextended('audit_log:' || :tenantId, 0))
                """, Map.of(), (rs, n) -> Boolean.TRUE);
        Optional<Head> head = queryAtMostOne("""
                SELECT seq, entry_hash
                  FROM audit_log
                 WHERE tenant_id = :tenantId
                 ORDER BY seq DESC
                 LIMIT 1
                """, Map.of(), (rs, n) -> new Head(rs.getLong("seq"), rs.getString("entry_hash")));
        long seq = head.map(h -> h.seq() + 1).orElse(1L);
        String prev = head.map(Head::entryHash).orElse(AuditChain.GENESIS);
        AuditRecord record = new AuditRecord(tenant, seq, entry, prev,
                AuditChain.entryHash(prev, AuditChain.canonicalEntry(tenant, seq, entry)));

        Map<String, Object> params = new HashMap<>();
        params.put("seq", seq);
        params.put("at", Timestamp.from(entry.at()));
        params.put("actorSubject", entry.actorSubject());
        params.put("actorRole", entry.actorRole());
        params.put("action", entry.action().name());
        params.put("targetKind", entry.targetKind());
        params.put("targetId", entry.targetId());
        params.put("detail", JSON.writeValueAsString(entry.detail()));
        params.put("prevHash", record.prevHash());
        params.put("entryHash", record.entryHash());
        update("""
                INSERT INTO audit_log (tenant_id, seq, at, actor_subject, actor_role, action, target_kind, target_id, detail,
                                       prev_hash, entry_hash)
                VALUES (:tenantId, :seq, :at, :actorSubject, :actorRole, :action, :targetKind, :targetId, CAST(:detail AS jsonb),
                        :prevHash, :entryHash)
                """, params);
        return record;
    }

    @Override
    public List<AuditRecord> readAll() {
        TenantId tenant = TenantContext.current();
        RowMapper<AuditRecord> mapper = (rs, n) -> new AuditRecord(tenant, rs.getLong("seq"),
                new AuditEntry(rs.getTimestamp("at").toInstant(), rs.getString("actor_subject"), rs.getString("actor_role"),
                        AuditAction.valueOf(rs.getString("action")), rs.getString("target_kind"), rs.getString("target_id"),
                        JSON.readTree(rs.getString("detail"))),
                rs.getString("prev_hash"), rs.getString("entry_hash"));
        return query("""
                SELECT seq, at, actor_subject, actor_role, action, target_kind, target_id, detail::text AS detail, prev_hash, entry_hash
                  FROM audit_log
                 WHERE tenant_id = :tenantId
                 ORDER BY seq
                """, Map.of(), mapper);
    }
}
