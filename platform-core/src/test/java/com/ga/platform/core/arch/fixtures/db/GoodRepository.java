package com.ga.platform.core.arch.fixtures.db;

import java.sql.ResultSet;
import java.sql.SQLException;

/** 하위 저장소가 ResultSet(JDBC 패키지)을 쓰는 것은 허용된다. */
public final class GoodRepository extends BaseRepository {

    public GoodRepository(Gateway gateway) {
        super(gateway);
    }

    String map(ResultSet rs) throws SQLException {
        return rs.getString(1);
    }
}
