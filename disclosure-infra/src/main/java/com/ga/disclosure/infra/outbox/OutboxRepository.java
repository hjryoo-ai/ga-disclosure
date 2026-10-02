package com.ga.disclosure.infra.outbox;

import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link OutboxPort} 어댑터. 적재는 테넌트 단위로 직렬화한다 — 트랜잭션 범위 어드바이저리 잠금(테넌트 키)을 잡은 뒤 머리를 읽어 seq = 머리 + 1로
 * 쓰고 머리를 옮긴다(V8 GD106이 같은 규칙을 이중으로 강제한다). 첫 이벤트는 머리 행이 아직 없어 행 잠금으로는 직렬화할 수 없으므로 어드바이저리
 * 잠금을 쓴다(같은 테넌트의 동시 첫 적재가 PK 충돌로 실패하지 않게). 잠금 순서는 업무 잠금(확인서 → 카운터 → 체인 머리) 다음, 마지막이다.
 * 적재 전에 envelope 전체를 계약 스키마로 검증하고 위반이면 명령 오류(트랜잭션 롤백)다. 쓰기 SQL은 {@link #append}에만.
 */
@Repository
public class OutboxRepository extends TenantScopedRepository implements OutboxPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public OutboxRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public long append(EventType type, String aggregateId, Instant occurredAt, JsonNode payload) {
        lockTenantOutbox();
        long head = queryAtMostOne("""
                SELECT seq
                  FROM outbox_head
                 WHERE tenant_id = :tenantId
                   FOR UPDATE
                """, Map.of(), (rs, n) -> rs.getLong("seq")).orElse(0L);
        long seq = head + 1;
        UUID eventId = UUID.randomUUID();
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("seq", seq);
        envelope.put("type", type.name());
        envelope.put("version", type.version());
        envelope.put("occurredAt", occurredAt.toString());
        envelope.putObject("aggregate").put("kind", type.aggregateKind()).put("id", aggregateId);
        envelope.set("payload", payload.deepCopy());
        List<String> problems = EventContract.validate(envelope);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(type + " event violates contracts/events/v1: " + problems);
        }
        Map<String, Object> p = new HashMap<>();
        p.put("seq", seq);
        p.put("eventId", eventId);
        p.put("type", type.name());
        p.put("version", type.version());
        p.put("occurredAt", Timestamp.from(occurredAt));
        p.put("aggregateKind", type.aggregateKind());
        p.put("aggregateId", aggregateId);
        p.put("payload", JSON.writeValueAsString(payload));
        update("""
                INSERT INTO outbox_event (tenant_id, seq, event_id, type, version, occurred_at, aggregate_kind, aggregate_id, payload)
                VALUES (:tenantId, :seq, :eventId, :type, :version, :occurredAt, :aggregateKind, :aggregateId, CAST(:payload AS jsonb))
                """, p);
        int moved = head == 0
                ? update("INSERT INTO outbox_head (tenant_id, seq) VALUES (:tenantId, :seq)", Map.of("seq", seq))
                : update("UPDATE outbox_head SET seq = :seq WHERE tenant_id = :tenantId AND seq = :previous",
                        Map.of("seq", seq, "previous", head));
        if (moved != 1) {
            throw new IllegalStateException("outbox head did not advance to " + seq);
        }
        return seq;
    }

    /** 테넌트 아웃박스 직렬화(트랜잭션 끝에 풀린다). */
    private void lockTenantOutbox() {
        query("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended('ga.outbox|' || :tenantId, 0))) l", Map.of(),
                (rs, n) -> rs.getInt("locked"));
    }
}
