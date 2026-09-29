package com.ga.platform.core.arch.fixtures.db;

import javax.sql.DataSource;

/**
 * 인프라로 지정된 클래스(TenantSessionBinder 역할)는 허용된다. 그 안의 중첩 클래스는 private·package-private이면
 * 허용 범위에 들어가지만, public 중첩 클래스는 밖에서 쓸 수 있는 통로이므로 위반이다.
 */
public final class Binder {

    private final DataSource dataSource;

    public Binder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    boolean ready() {
        return dataSource != null;
    }

    private static final class HiddenHelper {
        boolean ready(DataSource ds) {
            return ds != null;
        }
    }

    public static final class LeakyNested {
        public boolean ready(DataSource ds) {
            return ds != null && new HiddenHelper().ready(ds);
        }
    }
}
