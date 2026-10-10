package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V22 롤(8 계획 승인 Q1·Q3): 헬스 롤은 {@code SELECT 1}과 이력 표 {@code (version, success)}만 읽고, 백업 롤은 일반 세션으로 들어오지 못하며 표·정의자 함수
 * 권한이 0이다. 카탈로그 단언(권한 표)과 실제 실행(모든 표 SELECT → 42501)을 둘 다 한다 — 마이그레이션 시점 단언이 그 뒤의 GRANT를 못 보기 때문이다.
 */
class V22RolesIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    @Test
    void healthRoleReadsOnlyTheMigrationVersion() throws SQLException {
        try (Connection c = DB.healthDataSource().getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1, current_user")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(2)).isEqualTo(PostgresHarness.HEALTH);
            }
            try (ResultSet rs = st.executeQuery("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL")) {
                assertThat(rs.next()).isTrue();
            }
            assertDenied(st, "SELECT script FROM flyway_schema_history");
            assertDenied(st, "SELECT * FROM flyway_schema_history");
        }
    }

    @Test
    void backupRoleCannotOpenAnOrdinarySession() {
        assertThatThrownBy(() -> DB.dataSourceAs(PostgresHarness.BACKUP, PostgresHarness.BACKUP_PASSWORD).getConnection().close())
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
    }

    /** 실제 실행: superuser 세션에서 그 롤로 바꿔 public의 모든 표·뷰를 스키마 한정 이름으로 SELECT → 전부 42501(백업 롤은 스키마 USAGE부터 없다). */
    @ParameterizedTest
    @ValueSource(strings = {PostgresHarness.HEALTH, PostgresHarness.BACKUP})
    void everyTableSelectIsRefused(String role) throws SQLException {
        List<String> relations = relations();
        assertThat(relations).hasSizeGreaterThan(30).contains("audit_log", "disclosure", "customer_ref", "tenant_kek", "flyway_schema_history");
        List<String> readable = new ArrayList<>();
        try (Connection c = DB.superuserDataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("SET ROLE " + role);
            for (String r : relations) {
                try {
                    st.executeQuery("SELECT * FROM public." + r + " LIMIT 0").close();
                    readable.add(r);
                } catch (SQLException e) {
                    assertThat(e.getSQLState()).as(r).isEqualTo("42501");
                }
            }
            st.execute("RESET ROLE");
        }
        assertThat(readable).as("%s 일반 SELECT", role).isEmpty();
    }

    /** 카탈로그: 표·열·정의자 함수 권한(V22 단언과 같은 질의를 지금 다시) — 마이그레이션 뒤 GRANT가 생기면 여기서 잡힌다. */
    @ParameterizedTest
    @ValueSource(strings = {PostgresHarness.HEALTH, PostgresHarness.BACKUP})
    void catalogShowsNoPrivilegeBeyondTheTwoColumns(String role) throws SQLException {
        try (Connection c = DB.superuserDataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(strings(st, """
                    SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                       AND has_table_privilege('%s', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')""".formatted(role)))
                    .as("표 권한").isEmpty();
            assertThat(strings(st, """
                    SELECT c.relname || '.' || a.attname || ':' || has_column_privilege('%1$s', c.oid, a.attnum, 'INSERT, UPDATE, REFERENCES')
                      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                       AND has_column_privilege('%1$s', c.oid, a.attnum, 'SELECT, INSERT, UPDATE, REFERENCES')""".formatted(role)))
                    .as("열 권한")
                    .containsExactlyInAnyOrderElementsOf(PostgresHarness.HEALTH.equals(role)
                            ? List.of("flyway_schema_history.version:false", "flyway_schema_history.success:false")
                            : List.of());
            assertThat(strings(st, """
                    SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                     WHERE n.nspname = 'public' AND p.prosecdef AND has_function_privilege('%s', p.oid, 'EXECUTE')""".formatted(role)))
                    .as("정의자 함수").isEmpty();
            assertThat(strings(st, """
                    SELECT r.rolname FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid JOIN pg_roles x ON x.oid = m.member
                     WHERE x.rolname = '%s'""".formatted(role))).as("멤버십").isEmpty();
            assertThat(strings(st, """
                    SELECT rolreplication || ',' || rolbypassrls || ',' || rolsuper || ',' || has_schema_privilege('%1$s', 'public', 'CREATE')
                           || ',' || has_database_privilege('%1$s', current_database(), 'CONNECT')
                      FROM pg_roles WHERE rolname = '%1$s'""".formatted(role)))
                    .as("replication,bypassrls,super,create,connect")
                    .containsExactly(PostgresHarness.HEALTH.equals(role) ? "false,false,false,false,true" : "true,false,false,false,false");
        }
    }

    private static List<String> relations() throws SQLException {
        try (Connection c = DB.superuserDataSource().getConnection(); Statement st = c.createStatement()) {
            return strings(st, """
                    SELECT quote_ident(c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm') ORDER BY 1""");
        }
    }

    private static List<String> strings(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static void assertDenied(Statement st, String sql) {
        assertThatThrownBy(() -> st.executeQuery(sql).close()).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
    }
}
