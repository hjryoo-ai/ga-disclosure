package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.workflow.sign.NotificationStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 서명 링크 통지 아웃박스(V12 {@code notification_outbox}). 전이는 전부 조건부(PENDING일 때만 1행)이고 GD122가 한 번 더 강제한다. 삭제 없음(앱 롤에 DELETE
 * 권한이 없다). 전화번호·토큰은 이 클래스에 들어오지 않는다.
 */
@Repository
public class NotificationRepository extends TenantScopedRepository implements NotificationStore {

    private static final RowMapper<Pending> MAPPER = (rs, n) -> new Pending(rs.getObject("notification_id", UUID.class),
            new CustomerRef(rs.getString("customer_ref")), rs.getObject("session_id", UUID.class), rs.getInt("attempts"),
            rs.getTimestamp("next_attempt_at").toInstant());

    public NotificationRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public void insertPending(UUID notificationId, CustomerRef recipient, UUID sessionId, Instant at) {
        update("""
                INSERT INTO notification_outbox (tenant_id, notification_id, kind, customer_ref, session_id, status, attempts, next_attempt_at,
                                                 created_at)
                VALUES (:tenantId, :id, 'SIGN_LINK', :customerRef, :sessionId, 'PENDING', 0, :at, :at)
                """, Map.of("id", notificationId, "customerRef", recipient.value(), "sessionId", sessionId, "at", Timestamp.from(at)));
    }

    @Override
    public List<UUID> due(Instant asOf, int limit) {
        return query("""
                SELECT notification_id
                  FROM notification_outbox
                 WHERE tenant_id = :tenantId
                   AND status = 'PENDING'
                   AND next_attempt_at <= :asOf
                 ORDER BY next_attempt_at, notification_id
                 LIMIT :limit
                """, Map.of("asOf", Timestamp.from(asOf), "limit", limit), (rs, n) -> rs.getObject(1, UUID.class));
    }

    @Override
    public Optional<Pending> lockDue(UUID notificationId, Instant asOf) {
        return queryAtMostOne("""
                SELECT notification_id, customer_ref, session_id, attempts, next_attempt_at
                  FROM notification_outbox
                 WHERE tenant_id = :tenantId
                   AND notification_id = :id
                   AND status = 'PENDING'
                   AND next_attempt_at <= :asOf
                   FOR UPDATE SKIP LOCKED
                """, Map.of("id", notificationId, "asOf", Timestamp.from(asOf)), MAPPER);
    }

    @Override
    public Optional<Pending> lockPending(UUID notificationId) {
        return queryAtMostOne("""
                SELECT notification_id, customer_ref, session_id, attempts, next_attempt_at
                  FROM notification_outbox
                 WHERE tenant_id = :tenantId
                   AND notification_id = :id
                   AND status = 'PENDING'
                   FOR UPDATE
                """, Map.of("id", notificationId), MAPPER);
    }

    @Override
    public void markSent(UUID notificationId, Instant at) {
        one(update("""
                UPDATE notification_outbox
                   SET status = 'SENT', sent_at = :at, closed_at = :at
                 WHERE tenant_id = :tenantId AND notification_id = :id AND status = 'PENDING'
                """, Map.of("at", Timestamp.from(at), "id", notificationId)), notificationId);
    }

    @Override
    public void recordFailure(UUID notificationId, Instant nextAttemptAt, String errorCode) {
        one(update("""
                UPDATE notification_outbox
                   SET attempts = attempts + 1, next_attempt_at = :next, last_error_code = :code
                 WHERE tenant_id = :tenantId AND notification_id = :id AND status = 'PENDING'
                """, Map.of("next", Timestamp.from(nextAttemptAt), "code", errorCode, "id", notificationId)), notificationId);
    }

    @Override
    public void close(UUID notificationId, Closed status, String errorCode, boolean countAttempt, Instant at) {
        one(update("""
                UPDATE notification_outbox
                   SET status = :status, last_error_code = :code, closed_at = :at,
                       attempts = attempts + (CASE WHEN :count THEN 1 ELSE 0 END)
                 WHERE tenant_id = :tenantId AND notification_id = :id AND status = 'PENDING'
                """, Map.of("status", status.name(), "code", errorCode, "at", Timestamp.from(at), "count", countAttempt, "id", notificationId)),
                notificationId);
    }

    private static void one(int rows, UUID notificationId) {
        if (rows != 1) {
            throw new IllegalStateException("notification " + notificationId + " is no longer pending");
        }
    }
}
