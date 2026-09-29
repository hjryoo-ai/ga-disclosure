package com.ga.platform.core.arch.fixtures.db;

import javax.sql.DataSource;

/** ArchRulesTest 전용 표본: TenantJdbcGateway 역할(허용 목록에 FQN으로 등재되는 최상위 클래스). */
public final class Gateway {

    final DataSource dataSource;

    public Gateway(DataSource dataSource) {
        this.dataSource = dataSource;
    }
}
