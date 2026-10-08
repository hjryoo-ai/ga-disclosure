package com.ga.disclosure.infra.outbox;

import com.ga.disclosure.workflow.feed.EventFeedStore;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@link EventFeedStore} 어댑터(6A 피드). 읽기는 행을 계약 envelope 모양으로 다시 짠다(적재 때 계약 검증을 거친 값 그대로 — 시각은 DB 정밀도).
 * 쓰기 SQL은 {@link #markPublished} 하나 — {@code published_at}을 NULL에서 값으로 한 번(GD106, 앱 롤은 그 컬럼만 UPDATE 권한).
 */
@Repository
public class EventFeedRepository extends TenantScopedRepository implements EventFeedStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public EventFeedRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public long head() {
        return queryAtMostOne("SELECT seq FROM outbox_head WHERE tenant_id = :tenantId", Map.of(), (rs, n) -> rs.getLong("seq")).orElse(0L);
    }

    @Override
    public long acknowledged() {
        return query("""
                SELECT coalesce(max(seq), 0) AS acked
                  FROM outbox_event
                 WHERE tenant_id = :tenantId
                   AND published_at IS NOT NULL
                """, Map.of(), (rs, n) -> rs.getLong("acked")).getFirst();
    }

    @Override
    public List<JsonNode> after(long afterSeq, int limit) {
        return query("""
                SELECT seq, event_id, type, version, occurred_at, aggregate_kind, aggregate_id, payload::text AS payload
                  FROM outbox_event
                 WHERE tenant_id = :tenantId
                   AND seq > :afterSeq
                 ORDER BY seq
                 LIMIT :limit
                """, Map.of("afterSeq", afterSeq, "limit", limit), (rs, n) -> {
            ObjectNode envelope = JSON.createObjectNode();
            envelope.put("eventId", rs.getString("event_id"));
            envelope.put("seq", rs.getLong("seq"));
            envelope.put("type", rs.getString("type"));
            envelope.put("version", rs.getInt("version"));
            envelope.put("occurredAt", rs.getTimestamp("occurred_at").toInstant().toString());
            envelope.putObject("aggregate").put("kind", rs.getString("aggregate_kind")).put("id", rs.getString("aggregate_id"));
            envelope.set("payload", Canonicalizer.parseStrict(rs.getString("payload")));
            return (JsonNode) envelope;
        });
    }

    @Override
    public int markPublished(long upToSeq, Instant at) {
        return update("""
                UPDATE outbox_event
                   SET published_at = :at
                 WHERE tenant_id = :tenantId
                   AND seq <= :upToSeq
                   AND published_at IS NULL
                """, Map.of("upToSeq", upToSeq, "at", Timestamp.from(at)));
    }
}
