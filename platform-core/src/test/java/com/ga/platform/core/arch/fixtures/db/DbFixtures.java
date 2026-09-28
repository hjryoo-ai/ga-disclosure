package com.ga.platform.core.arch.fixtures.db;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;

/** ArchRulesTest 전용 표본. {@link BaseRepository}가 TenantScopedRepository 역할이다. */
public final class DbFixtures {

    private DbFixtures() {
    }

    public abstract static class BaseRepository {
        private final DataSource dataSource;

        protected BaseRepository(Gateway gateway) {
            this.dataSource = gateway.dataSource;
        }

        protected final boolean hasDataSource() {
            return dataSource != null;
        }

        public static final class Gateway {
            final DataSource dataSource;

            public Gateway(DataSource dataSource) {
                this.dataSource = dataSource;
            }
        }
    }

    /** 하위 저장소가 ResultSet(JDBC 패키지)을 쓰는 것은 허용된다. */
    public static final class GoodRepository extends BaseRepository {
        public GoodRepository(Gateway gateway) {
            super(gateway);
        }

        String map(ResultSet rs) throws SQLException {
            return rs.getString(1);
        }
    }

    /** 하위 저장소라도 DataSource를 직접 쥐면 위반이다. */
    public static final class SneakyRepository extends BaseRepository {
        private final DataSource raw;

        public SneakyRepository(Gateway gateway, DataSource raw) {
            super(gateway);
            this.raw = raw;
        }

        boolean hasRaw() {
            return raw != null;
        }
    }

    /** 저장소가 아닌 클래스의 JDBC 사용은 위반이다. */
    public static final class RogueDao {
        String read(ResultSet rs) throws SQLException {
            return rs.getString(1);
        }
    }

    /** 인프라로 지정된 클래스(TenantSessionBinder 역할)는 허용된다. */
    public static final class Binder {
        private final DataSource dataSource;

        public Binder(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        boolean ready() {
            return dataSource != null;
        }
    }
}
