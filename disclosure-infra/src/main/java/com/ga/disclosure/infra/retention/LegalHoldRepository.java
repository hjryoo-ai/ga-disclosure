package com.ga.disclosure.infra.retention;

import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.retention.LegalHoldStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 법적 보류(V9 {@code legal_hold}). 설정은 INSERT, 해제는 해제 세 컬럼만 NULL → 값(GD112), 삭제 없음. */
@Repository
public class LegalHoldRepository extends TenantScopedRepository implements LegalHoldStore {

    private static final String COLUMNS = "hold_id, disclosure_id, customer_ref, reason_code, reason_text, placed_by, placed_at, released_by, released_at, "
            + "release_reason_code";
    private static final RowMapper<Hold> MAPPER = (rs, n) -> {
        UUID disclosure = rs.getObject("disclosure_id", UUID.class);
        String customer = rs.getString("customer_ref");
        Timestamp released = rs.getTimestamp("released_at");
        return new Hold(rs.getObject("hold_id", UUID.class), disclosure == null ? null : DisclosureId.of(disclosure),
                customer == null ? null : new CustomerRef(customer), rs.getString("reason_code"), rs.getString("reason_text"), rs.getString("placed_by"),
                rs.getTimestamp("placed_at").toInstant(), rs.getString("released_by"), released == null ? null : released.toInstant(),
                rs.getString("release_reason_code"));
    };

    public LegalHoldRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public void insert(Hold hold) {
        Map<String, Object> p = new HashMap<>();
        p.put("holdId", hold.holdId());
        p.put("disclosureId", hold.disclosureOrNull() == null ? null : hold.disclosureOrNull().value());
        p.put("customerRef", hold.customerOrNull() == null ? null : hold.customerOrNull().value());
        p.put("reasonCode", hold.reasonCode());
        p.put("reasonText", hold.reasonTextOrNull());
        p.put("placedBy", hold.placedBy());
        p.put("placedAt", Timestamp.from(hold.placedAt()));
        update("""
                INSERT INTO legal_hold (tenant_id, hold_id, disclosure_id, customer_ref, reason_code, reason_text, placed_by, placed_at)
                VALUES (:tenantId, :holdId, :disclosureId, :customerRef, :reasonCode, :reasonText, :placedBy, :placedAt)
                """, p);
    }

    @Override
    public boolean release(UUID holdId, String by, Instant at, String reasonCode) {
        return update("""
                UPDATE legal_hold
                   SET released_by = :by, released_at = :at, release_reason_code = :code
                 WHERE tenant_id = :tenantId AND hold_id = :holdId AND released_at IS NULL
                """, Map.of("by", by, "at", Timestamp.from(at), "code", reasonCode, "holdId", holdId)) == 1;
    }

    @Override
    public List<Hold> page(Optional<Position> after, int limit) {
        Map<String, Object> p = new HashMap<>();
        p.put("limit", limit);
        String keyset = "";
        if (after.isPresent()) {
            keyset = " AND (placed_at, hold_id) < (:afterAt, :afterId)";
            p.put("afterAt", java.sql.Timestamp.from(after.get().placedAt()));
            p.put("afterId", after.get().holdId());
        }
        return query("SELECT " + COLUMNS + " FROM legal_hold WHERE tenant_id = :tenantId" + keyset
                + " ORDER BY placed_at DESC, hold_id DESC LIMIT :limit", p, MAPPER);
    }

    @Override
    public Optional<Hold> find(UUID holdId) {
        return queryAtMostOne("SELECT " + COLUMNS + " FROM legal_hold WHERE tenant_id = :tenantId AND hold_id = :holdId", Map.of("holdId", holdId), MAPPER);
    }

    @Override
    public Optional<Hold> activeFor(DisclosureId disclosure) {
        return queryAtMostOne("SELECT " + COLUMNS + " FROM legal_hold WHERE tenant_id = :tenantId AND disclosure_id = :id AND released_at IS NULL",
                Map.of("id", disclosure.value()), MAPPER);
    }

    @Override
    public Optional<Hold> activeFor(CustomerRef customer) {
        return queryAtMostOne("SELECT " + COLUMNS + " FROM legal_hold WHERE tenant_id = :tenantId AND customer_ref = :ref AND released_at IS NULL",
                Map.of("ref", customer.value()), MAPPER);
    }
}
