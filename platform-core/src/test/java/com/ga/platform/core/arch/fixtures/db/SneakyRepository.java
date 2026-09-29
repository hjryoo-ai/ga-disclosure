package com.ga.platform.core.arch.fixtures.db;

import javax.sql.DataSource;

/** 하위 저장소라도 DataSource를 직접 쥐면 위반이다. */
public final class SneakyRepository extends BaseRepository {

    private final DataSource raw;

    public SneakyRepository(Gateway gateway, DataSource raw) {
        super(gateway);
        this.raw = raw;
    }

    boolean hasRaw() {
        return raw != null;
    }
}
