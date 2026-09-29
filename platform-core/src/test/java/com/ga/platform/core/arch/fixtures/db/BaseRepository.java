package com.ga.platform.core.arch.fixtures.db;

import javax.sql.DataSource;

/** ArchRulesTest 전용 표본: TenantScopedRepository 역할. */
public abstract class BaseRepository {

    private final DataSource dataSource;

    protected BaseRepository(Gateway gateway) {
        this.dataSource = gateway.dataSource;
    }

    protected final boolean hasDataSource() {
        return dataSource != null;
    }
}
