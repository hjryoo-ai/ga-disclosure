package com.ga.platform.spring.jdbc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 테스트용 저장소. 운영 저장소와 같은 경로(기반 클래스의 보호 메서드)만 쓴다. */
final class ProbeRepository extends TenantScopedRepository {

    ProbeRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    List<String> scoped() {
        return query("SELECT name FROM probe WHERE tenant_id = :tenantId", Map.of(), (rs, i) -> rs.getString(1));
    }

    Optional<String> scopedAtMostOne() {
        return queryAtMostOne("SELECT name FROM probe WHERE tenant_id = :tenantId", Map.of(), (rs, i) -> rs.getString(1));
    }

    List<String> unscoped() {
        return query("SELECT name FROM probe WHERE name = :name", Map.of("name", "x"), (rs, i) -> rs.getString(1));
    }

    List<String> lookalikePlaceholder() {
        return query("SELECT name FROM probe WHERE tenant_id = :tenantIdx", Map.of("tenantIdx", "T1"), (rs, i) -> rs.getString(1));
    }

    int callerSuppliedTenant() {
        return update("DELETE FROM probe WHERE tenant_id = :tenantId", Map.of(TENANT_PARAM, "OTHER"));
    }

    int insert(String name) {
        return update("INSERT INTO probe (tenant_id, name) VALUES (:tenantId, :name)", Map.of("name", name));
    }
}
