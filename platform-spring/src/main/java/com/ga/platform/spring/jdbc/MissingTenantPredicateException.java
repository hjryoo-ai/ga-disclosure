package com.ga.platform.spring.jdbc;

/** 저장소 SQL에 테넌트 바인드 변수({@code :tenantId})가 없다. DB에 보내기 전에 거부한다. */
public final class MissingTenantPredicateException extends IllegalStateException {

    public MissingTenantPredicateException(String sql) {
        super("SQL without :" + TenantScopedRepository.TENANT_PARAM + " is rejected (CLAUDE.md 절대 규칙 5): " + sql.strip());
    }
}
