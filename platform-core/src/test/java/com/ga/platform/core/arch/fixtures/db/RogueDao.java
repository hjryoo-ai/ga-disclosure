package com.ga.platform.core.arch.fixtures.db;

import java.sql.ResultSet;
import java.sql.SQLException;

/** 저장소가 아닌 클래스의 JDBC 사용은 위반이다. */
public final class RogueDao {

    String read(ResultSet rs) throws SQLException {
        return rs.getString(1);
    }
}
