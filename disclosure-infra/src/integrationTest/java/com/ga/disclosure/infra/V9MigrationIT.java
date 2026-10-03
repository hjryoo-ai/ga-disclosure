package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V9 이관(승인 Q8): V1 {@code audit_anchor}는 행이 0일 때만 제거한다 — 행이 있으면 V9 전체가 실패하고 테이블·데이터는 V8 그대로 남는다.
 * 비어 있으면 제거되고, 옛 {@code void_reason} 컬럼도 사라진다(V8 이관의 둘째 단계).
 */
class V9MigrationIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    private static void inTenant(DataSource migrator, String tenant, PostgresHarness.SqlWork work) throws SQLException {
        try (Connection c = migrator.getConnection()) {
            c.setAutoCommit(false);
            PostgresHarness.setTenant(c, tenant);
            work.run(c);
            c.commit();
        }
    }

    private static String text(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection()) {
            return SeedData.call(c, sql);
        }
    }

    @Test
    void aNonEmptyAuditAnchorStopsV9AndKeepsTheData() throws SQLException {
        DataSource migrator = DB.emptyDatabase("v9_kept");
        PostgresHarness.migrate(migrator, "8");
        inTenant(migrator, "MIG9", c -> {
            SeedData.tenant(c, "MIG9");
            SeedData.exec(c, """
                    INSERT INTO audit_anchor (tenant_id, anchored_at, head_seq, head_hash)
                    VALUES ('MIG9', TIMESTAMPTZ '2026-09-24 00:00:00+09', 1, repeat('1', 64))
                    """);
        });
        assertThatThrownBy(() -> PostgresHarness.migrate(migrator, "9"))
                .hasStackTraceContaining("audit_anchor holds 1 row(s); V9 does not drop data");
        assertThat(text(migrator, "SELECT max(version) FROM flyway_schema_history WHERE success")).isEqualTo("8");
        assertThat(text(migrator, "SELECT to_regclass('audit_anchor')::text")).isEqualTo("audit_anchor");
        assertThat(text(migrator, "SELECT to_regclass('anchor')::text")).isNull();
    }

    @Test
    void anEmptyAuditAnchorIsDroppedWithTheLegacyVoidReason() throws SQLException {
        DataSource migrator = DB.emptyDatabase("v9_clean");
        PostgresHarness.migrate(migrator, "9");
        assertThat(text(migrator, "SELECT to_regclass('audit_anchor')::text")).isNull();
        assertThat(text(migrator, "SELECT to_regclass('anchor')::text")).isEqualTo("anchor");
        assertThat(text(migrator, """
                SELECT count(*) FROM information_schema.columns WHERE table_name = 'disclosure' AND column_name = 'void_reason'
                """)).isEqualTo("0");
        assertThat(text(migrator, """
                SELECT string_agg(column_name, ',' ORDER BY column_name) FROM information_schema.columns
                 WHERE table_name IN ('disclosure', 'customer_ref') AND column_name LIKE 'destroyed%'
                """)).isEqualTo("destroyed_at,destroyed_at,destroyed_by,destroyed_by");
    }
}
