package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2: disclosure_app 세션에서 app.tenant_id 미설정 시 모든 테넌트 테이블 0행, 설정 시 해당 테넌트 행만(tenant 포함).
 * C3: disclosure_app은 RLS를 우회·해제하거나 트리거를 비활성화할 수 없다.
 * Phase 2 P7: 검사 대상 테이블은 손목록이 아니라 DB 카탈로그에서 읽는다(public 스키마 전 테이블 − flyway_schema_history).
 * 새 테이블은 자동으로 모든 검사에 들어가고, 개수 고정 단언이 추가 사실을 드러낸다.
 */
class RlsIsolationIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    /** V8 기준 테넌트 테이블 수(V8 signature_evidence·outbox_head·outbox_event 추가). 테이블을 추가하는 마이그레이션은 이 값을 함께 고친다(추가가 조용히 지나가지 않게). */
    private static final int EXPECTED_TABLE_COUNT = 27;

    static final List<String> TABLES = catalogTables();

    private static List<String> catalogTables() {
        List<String> tables = new ArrayList<>();
        DB.seed("CATALOG", c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                    SELECT c.relname
                      FROM pg_class c
                     WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p')
                       AND c.relname <> 'flyway_schema_history'
                     ORDER BY c.relname
                    """)) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        });
        return List.copyOf(tables);
    }

    @Test
    void catalogListsEveryTenantTableAndTheCountIsPinned() {
        assertThat(TABLES).hasSize(EXPECTED_TABLE_COUNT)
                .contains("tenant", "customer_ref", "customer_data_key", "catalog_import", "product_catalog", "compliance_flag", "review",
                        "disclosure_counter", "disclosure_chain_head", "document_key");
    }

    /** 모든 테이블의 첫 컬럼은 tenant_id다(설계서 §5). */
    @ParameterizedTest
    @FieldSource("TABLES")
    void tenantIdIsTheFirstColumn(String table) {
        DB.seed(A, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT attname FROM pg_attribute
                     WHERE attrelid = ('public.' || ?)::regclass AND attnum = 1
                    """)) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).as(table).isEqualTo("tenant_id");
                }
            }
        });
    }
    private static final String A = SeedData.uniqueTenant("RLS_A");
    private static final String B = SeedData.uniqueTenant("RLS_B");

    @BeforeAll
    static void seedTwoTenants() {
        DB.seed(A, c -> SeedData.everyTable(c, A));
        DB.seed(B, c -> SeedData.everyTable(c, B));
    }

    @ParameterizedTest
    @FieldSource("TABLES")
    void unsetTenantSeesZeroRows(String table) {
        assertThat(DB.<Long>asApp(null, c -> count(c, table, null))).isZero();
    }

    @ParameterizedTest
    @FieldSource("TABLES")
    void emptyTenantSettingSeesZeroRows(String table) {
        // 트랜잭션이 끝난 뒤 커스텀 설정은 NULL이 아니라 빈 문자열로 남는다(풀링된 커넥션 재사용 상황).
        assertThat(DB.<Long>asApp(null, c -> {
            try (Statement s = c.createStatement()) {
                s.execute("SET app.tenant_id = ''");
            }
            return count(c, table, null);
        })).isZero();
    }

    @ParameterizedTest
    @FieldSource("TABLES")
    void boundTenantSeesOnlyItsOwnRows(String table) {
        for (String tenant : List.of(A, B)) {
            String other = tenant.equals(A) ? B : A;
            long visible = DB.asApp(tenant, c -> count(c, table, null));
            long own = DB.asApp(tenant, c -> count(c, table, tenant));
            long foreign = DB.asApp(tenant, c -> count(c, table, other));
            assertThat(visible).as("%s rows visible to %s", table, tenant).isPositive().isEqualTo(own);
            assertThat(foreign).as("%s rows of %s visible to %s", table, other, tenant).isZero();
        }
    }

    @ParameterizedTest
    @FieldSource("TABLES")
    void everyTenantTableHasForcedRlsAndTenantPolicy(String table) {
        DB.seed(A, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT c.relrowsecurity, c.relforcerowsecurity, p.qual, p.with_check
                      FROM pg_class c
                      JOIN pg_policies p ON p.tablename = c.relname AND p.policyname = 'tenant_isolation'
                     WHERE c.relname = ? AND c.relnamespace = 'public'::regnamespace
                    """)) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("policy on %s", table).isTrue();
                    assertThat(rs.getBoolean(1)).isTrue();
                    assertThat(rs.getBoolean(2)).isTrue();
                    assertThat(rs.getString(3)).contains("tenant_id").contains("app.tenant_id");
                    assertThat(rs.getString(4)).contains("tenant_id").contains("app.tenant_id");
                }
            }
        });
    }

    /**
     * 정책은 테이블마다 tenant_isolation 하나뿐이다. 유일한 예외는 tenant 테이블의 tenant_directory(V4, disclosure_operator 전용
     * SELECT) — 허용 목록을 명시해 새 정책이 조용히 추가되지 않게 한다.
     */
    @Test
    void policiesAreExactlyTenantIsolationPlusTheDirectoryException() {
        List<String> policies = new ArrayList<>();
        DB.seed(A, c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                    SELECT tablename || ':' || policyname || ':' || cmd || ':' || array_to_string(roles, ',')
                      FROM pg_policies WHERE schemaname = 'public' ORDER BY 1
                    """)) {
                while (rs.next()) {
                    policies.add(rs.getString(1));
                }
            }
        });
        List<String> expected = new ArrayList<>();
        TABLES.forEach(t -> expected.add(t + ":tenant_isolation:ALL:public"));
        expected.add("tenant:tenant_directory:SELECT:disclosure_operator");
        assertThat(policies).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void operatorSeesEveryTenantIdAndNothingElse() throws SQLException {
        try (Connection c = DB.operatorDataSource().getConnection(); Statement s = c.createStatement()) {
            List<String> ids = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT tenant_id FROM tenant")) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            assertThat(ids).contains(A, B);
            for (String sql : List.of("SELECT name FROM tenant", "SELECT * FROM tenant", "SELECT tenant_id FROM rule_version",
                    "SELECT count(*) FROM audit_log", "UPDATE tenant SET tenant_id = tenant_id")) {
                assertThatThrownBy(() -> s.executeQuery(sql)).as(sql)
                        .isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
            }
        }
    }

    @Test
    void cannotWriteRowsOfAnotherTenant() {
        assertThatThrownBy(() -> DB.asApp(A, c -> SeedData.exec(c,
                "INSERT INTO identity_link (tenant_id, subject, agent_id, roles, org_path) VALUES (?, 'x', 'y', ARRAY['AGENT'], '/')", B)))
                .isInstanceOf(PostgresHarness.UncheckedSqlException.class)
                .satisfies(e -> assertThat(((PostgresHarness.UncheckedSqlException) e).sqlState()).isEqualTo("42501"));
        assertThat(DB.<Integer>asApp(A, c -> SeedData.exec(c, "UPDATE identity_link SET org_path = '/X' WHERE tenant_id = ?", B))).isZero();
        assertThat(DB.<Integer>asApp(A, c -> SeedData.exec(c, "DELETE FROM compliance_flag WHERE tenant_id = ?", B))).isZero();
    }

    @Test
    void cannotMoveOwnRowToAnotherTenant() {
        assertThatThrownBy(() -> DB.asApp(A, c -> SeedData.exec(c,
                "UPDATE identity_link SET tenant_id = ? WHERE tenant_id = ?", B, A)))
                .isInstanceOf(PostgresHarness.UncheckedSqlException.class)
                .satisfies(e -> assertThat(((PostgresHarness.UncheckedSqlException) e).sqlState()).isEqualTo("42501"));
    }

    // ------------------------------------------------------------------ C3

    @ParameterizedTest
    @ValueSource(strings = {
            "SET ROLE disclosure_migrator",
            "SET ROLE postgres",
            "SET SESSION AUTHORIZATION disclosure_migrator",
            "SET session_replication_role = replica",
            "ALTER TABLE disclosure DISABLE ROW LEVEL SECURITY",
            "ALTER TABLE disclosure NO FORCE ROW LEVEL SECURITY",
            "ALTER TABLE disclosure DISABLE TRIGGER ALL",
            "ALTER TABLE disclosure DISABLE TRIGGER trg_disclosure_guard_update",
            "ALTER TABLE signature DISABLE TRIGGER USER",
            "DROP POLICY tenant_isolation ON disclosure",
            "CREATE POLICY bypass ON disclosure USING (true)",
            "ALTER ROLE disclosure_app BYPASSRLS",
            "DROP TRIGGER trg_signature_guard_insert ON signature",
            "TRUNCATE audit_log",
            "TRUNCATE disclosure CASCADE",
            "CREATE TABLE shadow (tenant_id TEXT)",
            "CREATE OR REPLACE FUNCTION ga_append_only() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$",
            "SELECT * FROM flyway_schema_history",
    })
    void appRoleCannotBypassOrDisableProtections(String sql) {
        List<String> states = new ArrayList<>();
        assertThatThrownBy(() -> DB.asApp(A, c -> {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            } catch (SQLException e) {
                states.add(e.getSQLState());
                throw e;
            }
            return null;
        })).isInstanceOf(PostgresHarness.UncheckedSqlException.class);
        // 문법 오류·객체 없음이 아니라 권한 오류(42501 insufficient_privilege)로 거부되어야 한다.
        assertThat(states).singleElement().satisfies(state ->
                assertThat(state).as("SQLSTATE for: %s", sql).isEqualTo("42501"));
    }

    @Test
    void rowSecurityOffIsRejectedWhenPoliciesApply() {
        // row_security=off 는 설정 자체는 되지만, 정책이 적용되는 조회에서 오류가 난다 → 우회 불가.
        assertThatThrownBy(() -> DB.asApp(A, c -> {
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL row_security = off");
            }
            return count(c, "disclosure", null);
        })).isInstanceOf(PostgresHarness.UncheckedSqlException.class)
                .satisfies(e -> assertThat(((PostgresHarness.UncheckedSqlException) e).sqlState()).isEqualTo("42501"));
    }

    @Test
    void appRoleAttributesDenyBypass() {
        DB.seed(A, c -> {
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("""
                         SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolinherit
                           FROM pg_roles WHERE rolname = 'disclosure_app'
                         """)) {
                assertThat(rs.next()).isTrue();
                for (int i = 1; i <= 5; i++) {
                    assertThat(rs.getBoolean(i)).as("attribute %d", i).isFalse();
                }
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("""
                         SELECT count(*) FROM information_schema.role_table_grants
                          WHERE grantee = 'disclosure_app' AND privilege_type IN ('TRUNCATE', 'TRIGGER', 'REFERENCES')
                         """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isZero();
            }
        });
    }

    private static long count(Connection c, String table, String tenantFilter) throws SQLException {
        String sql = "SELECT count(*) FROM " + table + (tenantFilter == null ? "" : " WHERE tenant_id = ?");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (tenantFilter != null) {
                ps.setString(1, tenantFilter);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
