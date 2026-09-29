package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.enums.GateMode;
import com.ga.disclosure.domain.enums.IssuerMode;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

/** 자기 테넌트 행 조회. 다른 테넌트 행은 저장소 조건과 RLS 양쪽에서 보이지 않는다. */
@Repository
public class TenantRepository extends TenantScopedRepository {

    private static final RowMapper<TenantRecord> MAPPER = (rs, rowNum) -> new TenantRecord(
            TenantId.of(rs.getString("tenant_id")),
            rs.getString("name"),
            rs.getString("engine_base_url"),
            rs.getString("status"),
            rs.getBoolean("large_ga"),
            IssuerMode.valueOf(rs.getString("issuer_mode")),
            GateMode.valueOf(rs.getString("gate_mode")),
            rs.getString("params"));

    public TenantRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    /**
     * 바인딩된 테넌트의 행을 만든다(운영·데모 시드용). issuer_mode·gate_mode·params는 DB 기본값(V1)을 따른다.
     * 이미 있으면 0을 돌려준다.
     */
    public int insertCurrentIfAbsent(String name, String engineBaseUrl, boolean largeGa) {
        return update("""
                INSERT INTO tenant (tenant_id, name, engine_base_url, status, large_ga)
                VALUES (:tenantId, :name, :engineBaseUrl, 'ACTIVE', :largeGa)
                ON CONFLICT (tenant_id) DO NOTHING
                """, Map.of("name", name, "engineBaseUrl", engineBaseUrl, "largeGa", largeGa));
    }

    public Optional<TenantRecord> findCurrent() {
        return queryAtMostOne("""
                SELECT tenant_id, name, engine_base_url, status, large_ga, issuer_mode, gate_mode, params::text AS params
                  FROM tenant
                 WHERE tenant_id = :tenantId
                """, Map.of(), MAPPER);
    }
}
