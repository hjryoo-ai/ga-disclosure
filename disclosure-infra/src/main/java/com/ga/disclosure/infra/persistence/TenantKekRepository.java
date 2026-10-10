package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.kek.TenantKekStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link TenantKekStore} 어댑터({@code tenant_kek}, V21 — append + CURRENT→RETIRED 한 번, GD140). */
@Repository
public class TenantKekRepository extends TenantScopedRepository implements TenantKekStore {

    public TenantKekRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public Optional<String> current() {
        return queryAtMostOne("SELECT kek_id FROM tenant_kek WHERE tenant_id = :tenantId AND status = 'CURRENT'", Map.of(),
                (rs, n) -> rs.getString("kek_id"));
    }

    @Override
    public List<Registered> all() {
        return query("""
                SELECT kek_id, status, registered_at FROM tenant_kek WHERE tenant_id = :tenantId ORDER BY registered_at, kek_id
                """, Map.of(), (rs, n) -> new Registered(rs.getString("kek_id"), "CURRENT".equals(rs.getString("status")),
                rs.getTimestamp("registered_at").toInstant()));
    }

    @Override
    public void register(String kekId, Instant at, String by) {
        update("""
                UPDATE tenant_kek SET status = 'RETIRED', retired_at = :at WHERE tenant_id = :tenantId AND status = 'CURRENT'
                """, Map.of("at", Timestamp.from(at)));
        update("""
                INSERT INTO tenant_kek (tenant_id, kek_id, status, registered_at, registered_by)
                VALUES (:tenantId, :kekId, 'CURRENT', :at, :by)
                """, Map.of("kekId", kekId, "at", Timestamp.from(at), "by", by));
    }
}
