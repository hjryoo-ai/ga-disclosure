package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** disclosure_app으로 문장을 실행(롤백)하고 허용/거부(SQLSTATE)를 확인한다. */
final class TriggerAssertions {

    private TriggerAssertions() {
    }

    static void assertAllowed(PostgresHarness db, String tenant, String sql, Object... params) {
        int updated = db.asApp(tenant, c -> SeedData.exec(c, sql, params));
        assertThat(updated).as("rows affected by allowed statement: %s", sql).isEqualTo(1);
    }

    static void assertRejected(PostgresHarness db, String tenant, String expectedSqlState, String sql, Object... params) {
        assertThat(sqlStateOf(() -> db.asApp(tenant, c -> SeedData.exec(c, sql, params))))
                .as("SQLSTATE for: %s", sql)
                .isEqualTo(expectedSqlState);
    }

    static String sqlStateOf(Runnable action) {
        try {
            action.run();
        } catch (PostgresHarness.UncheckedSqlException e) {
            return e.sqlState();
        }
        return fail("statement was expected to be rejected but succeeded");
    }
}
