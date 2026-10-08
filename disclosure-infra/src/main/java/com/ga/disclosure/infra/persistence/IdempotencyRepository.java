package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.idempotency.IdempotencyRecord;
import com.ga.disclosure.workflow.idempotency.IdempotencyStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Idempotency-Key(V12 {@code idempotency_key}, GD120). 쓰기는 전부 조건부이고 허용 변경(첫 청구·인수·완료 1회·만료 뒤 삭제)은 트리거가 한 번 더 강제한다.
 * 만료 판정은 이 프로세스의 시계와 DB 시계 둘 다로 한다 — 트리거는 DB 시계로 "만료 전 삭제"를 거부하므로, 시계가 앞선 노드가 지우려다 실패하지 않게.
 */
@Repository
public class IdempotencyRepository extends TenantScopedRepository implements IdempotencyStore {

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, n) -> new IdempotencyRecord(rs.getString("request_hash"), rs.getInt("claim_seq"),
            rs.getTimestamp("claimed_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
            Optional.ofNullable(rs.getObject("response_status", Integer.class)), Optional.ofNullable(rs.getString("response_ref")),
            Optional.ofNullable(rs.getString("response_hash")));

    public IdempotencyRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    private static Map<String, Object> key(String subject, String key) {
        Map<String, Object> p = new HashMap<>();
        p.put("subject", subject);
        p.put("key", key);
        return p;
    }

    @Override
    public boolean claimNew(String subject, String key, String requestHash, Instant now, Instant expiresAt) {
        Map<String, Object> p = key(subject, key);
        p.put("hash", requestHash);
        p.put("now", Timestamp.from(now));
        p.put("expiresAt", Timestamp.from(expiresAt));
        return update("""
                INSERT INTO idempotency_key (tenant_id, actor_subject, idem_key, request_hash, claimed_at, created_at, expires_at)
                VALUES (:tenantId, :subject, :key, :hash, :now, :now, :expiresAt)
                ON CONFLICT (tenant_id, actor_subject, idem_key) DO NOTHING
                """, p) == 1;
    }

    @Override
    public Optional<IdempotencyRecord> lock(String subject, String key) {
        return queryAtMostOne("""
                SELECT request_hash, claim_seq, claimed_at, expires_at, response_status, response_ref::text AS response_ref, response_hash
                  FROM idempotency_key
                 WHERE tenant_id = :tenantId AND actor_subject = :subject AND idem_key = :key
                   FOR UPDATE
                """, key(subject, key), MAPPER);
    }

    @Override
    public boolean deleteExpired(String subject, String key, Instant now) {
        Map<String, Object> p = key(subject, key);
        p.put("now", Timestamp.from(now));
        return update("""
                DELETE FROM idempotency_key
                 WHERE tenant_id = :tenantId AND actor_subject = :subject AND idem_key = :key
                   AND expires_at < :now AND expires_at < now()
                """, p) == 1;
    }

    @Override
    public boolean takeOver(String subject, String key, int fromSeq, Instant now) {
        Map<String, Object> p = key(subject, key);
        p.put("fromSeq", fromSeq);
        p.put("now", Timestamp.from(now));
        return update("""
                UPDATE idempotency_key
                   SET claim_seq = claim_seq + 1, claimed_at = GREATEST(:now, claimed_at + INTERVAL '1 microsecond')
                 WHERE tenant_id = :tenantId AND actor_subject = :subject AND idem_key = :key
                   AND claim_seq = :fromSeq AND response_status IS NULL
                """, p) == 1;
    }

    @Override
    public boolean complete(String subject, String key, int claimSeq, int status, String responseRef, String responseHash) {
        Map<String, Object> p = key(subject, key);
        p.put("claimSeq", claimSeq);
        p.put("status", status);
        p.put("ref", responseRef);
        p.put("hash", responseHash);
        return update("""
                UPDATE idempotency_key
                   SET response_status = :status, response_ref = CAST(:ref AS jsonb), response_hash = :hash
                 WHERE tenant_id = :tenantId AND actor_subject = :subject AND idem_key = :key
                   AND claim_seq = :claimSeq AND response_status IS NULL
                """, p) == 1;
    }

    @Override
    public int purgeExpired(Instant now, int limit) {
        return update("""
                DELETE FROM idempotency_key
                 WHERE tenant_id = :tenantId
                   AND (actor_subject, idem_key) IN (SELECT actor_subject, idem_key
                                                       FROM idempotency_key
                                                      WHERE tenant_id = :tenantId AND expires_at < :now AND expires_at < now()
                                                      ORDER BY expires_at, actor_subject, idem_key
                                                      LIMIT :limit
                                                        FOR UPDATE SKIP LOCKED)
                """, Map.of("now", Timestamp.from(now), "limit", limit));
    }
}
