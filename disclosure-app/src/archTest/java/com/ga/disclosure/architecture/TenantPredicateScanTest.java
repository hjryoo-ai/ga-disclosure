package com.ga.disclosure.architecture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C6: disclosure-infra의 모든 .sql과 자바 문자열 SQL을 스캔해, tenant·flyway_schema_history를 제외한 테이블 접근 문장에
 * tenant_id 조건(또는 컬럼 지정)이 있는지 검사한다.
 */
class TenantPredicateScanTest {

    /**
     * 허용 목록: 공백 정규화·소문자화된 문장 → 사유. 비어 있는 상태로 시작한다.
     * 항목을 추가하려면 사유를 반드시 적는다(빈 사유는 {@link #allowlistEntriesHaveReasonsAndAreUsed()}가 거부).
     */
    static final Map<String, String> ALLOWLIST = Map.of();

    private static final Path INFRA_MAIN = Path.of(System.getProperty("ga.repoRoot"), "disclosure-infra", "src", "main");

    private static List<Path> sqlFiles;
    private static List<Path> javaFiles;
    private static Set<String> knownTables;

    @BeforeAll
    static void collect() throws IOException {
        sqlFiles = walk(".sql");
        javaFiles = walk(".java");
        knownTables = SqlTenantScanner.tablesDefinedIn(sqlFiles.stream().map(TenantPredicateScanTest::read).toList());
    }

    private static List<Path> walk(String suffix) throws IOException {
        try (Stream<Path> s = Files.walk(INFRA_MAIN)) {
            return s.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<SqlTenantScanner.Violation> scanAll() {
        List<SqlTenantScanner.Violation> all = new ArrayList<>();
        for (Path p : sqlFiles) {
            all.addAll(SqlTenantScanner.scan(INFRA_MAIN.relativize(p).toString(), read(p), knownTables));
        }
        for (Path p : javaFiles) {
            for (String literal : SqlTenantScanner.sqlLiteralsInJava(read(p))) {
                all.addAll(SqlTenantScanner.scan(INFRA_MAIN.relativize(p).toString(), literal, knownTables));
            }
        }
        return all;
    }

    @Test
    void scanCoversMigrationsAndRepositories() {
        assertThat(sqlFiles).extracting(p -> p.getFileName().toString())
                .contains("V1__init.sql", "V2__rls.sql", "V3__immutability.sql");
        // 테이블 목록은 마이그레이션의 CREATE TABLE에서 읽는다 — V5의 새 테이블 2개·V6 review가 손대지 않아도 스캔 대상이 된다(Phase 2 P7).
        assertThat(knownTables).hasSize(21).contains("tenant", "disclosure", "signature", "audit_log", "customer_data_key", "catalog_import",
                "review");
        long javaSqlCount = javaFiles.stream().mapToLong(p -> SqlTenantScanner.sqlLiteralsInJava(read(p)).size()).sum();
        assertThat(javaSqlCount).as("repository SQL literals found").isGreaterThanOrEqualTo(4);
    }

    @Test
    void everyTenantTableAccessHasTenantPredicate() {
        List<SqlTenantScanner.Violation> violations = scanAll().stream()
                .filter(v -> !ALLOWLIST.containsKey(v.statement()))
                .toList();
        assertThat(violations).as("tenant_id 없는 테이블 접근 문장(CLAUDE.md 절대 규칙 5)").isEmpty();
    }

    @Test
    void allowlistEntriesHaveReasonsAndAreUsed() {
        List<String> flagged = scanAll().stream().map(SqlTenantScanner.Violation::statement).toList();
        ALLOWLIST.forEach((statement, reason) -> {
            assertThat(reason).as("사유 없는 허용 목록 항목: %s", statement).isNotBlank();
            assertThat(flagged).as("쓰이지 않는 허용 목록 항목(제거할 것): %s", statement).contains(statement);
        });
    }

    // ------------------------------------------------------------------ 스캐너 자체의 음성 테스트(영구 보존)

    private static final Set<String> TABLES = Set.of("tenant", "disclosure", "disclosure_item", "rule_version");

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM disclosure WHERE status = 'SEALED'",
            "SELECT d.status FROM disclosure d JOIN disclosure_item i ON i.disclosure_id = d.disclosure_id",
            "UPDATE rule_version SET status = 'ACTIVE' WHERE rule_version_id = :id",
            "DELETE FROM disclosure_item WHERE disclosure_id = ?",
            "INSERT INTO disclosure_item (disclosure_id, item_no) VALUES (?, ?)",
            "INSERT INTO disclosure_item VALUES (?, ?)",
            "CREATE TABLE audit_shadow (seq BIGINT)",
            "CREATE INDEX ix_bad ON disclosure (status)",
            "CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$ BEGIN IF true THEN DELETE FROM disclosure WHERE disclosure_id = NULL; END IF; END $$",
            "DO $body$ BEGIN PERFORM 1 FROM rule_version WHERE scope = 'GLOBAL'; END $body$",
    })
    void scannerFlagsStatementsWithoutTenantPredicate(String sql) {
        assertThat(SqlTenantScanner.scan("inline", sql, TABLES)).as(sql).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM disclosure WHERE tenant_id = :tenantId AND status = 'SEALED'",
            "SELECT * FROM tenant",
            "SELECT count(*) FROM flyway_schema_history",
            "UPDATE rule_version SET status = 'ACTIVE' WHERE tenant_id = :tenantId AND rule_version_id = :id",
            "INSERT INTO disclosure_item (tenant_id, disclosure_id) VALUES (:tenantId, ?)",
            "CREATE INDEX ix_ok ON disclosure (tenant_id, status)",
            "-- SELECT * FROM disclosure\nSELECT 1",
            "SELECT 'FROM disclosure' AS text",
            "CREATE TRIGGER t BEFORE UPDATE OR DELETE ON disclosure FOR EACH ROW EXECUTE FUNCTION f()",
            "SELECT x IS DISTINCT FROM y",
    })
    void scannerAcceptsTenantScopedOrIrrelevantStatements(String sql) {
        assertThat(SqlTenantScanner.scan("inline", sql, TABLES)).as(sql).isEmpty();
    }

    @Test
    void javaLiteralExtractionFindsTextBlocksAndStrings() {
        String java = """
                class R {
                    // "SELECT * FROM disclosure" in a comment is ignored
                    String a = "SELECT * FROM disclosure WHERE x = 1";
                    String b = \"""
                            DELETE FROM disclosure_item
                             WHERE tenant_id = :tenantId
                            \""";
                    char q = '"';
                    String c = "not sql";
                }
                """;
        assertThat(SqlTenantScanner.sqlLiteralsInJava(java)).hasSize(2);
        assertThat(SqlTenantScanner.sqlLiteralsInJava(java).stream()
                .flatMap(l -> SqlTenantScanner.scan("R.java", l, TABLES).stream())).hasSize(1);
    }
}
