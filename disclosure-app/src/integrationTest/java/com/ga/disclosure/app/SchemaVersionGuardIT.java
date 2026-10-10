package com.ga.disclosure.app;

import com.ga.disclosure.app.api.ApiTestSupport;
import com.ga.disclosure.app.cli.OfflineCli;
import com.ga.disclosure.infra.migration.SchemaMigrator;
import com.ga.disclosure.infra.testing.PostgresHarness;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 마이그레이션 분리(8 계획 승인 "앱 기동 Flyway 제거 — 버전 불일치 기동 실패 시험"): 앱은 기동 때 스키마를 바꾸지 않고, 배포물 최고 버전과 DB 최고 성공
 * 버전이 다르면 뜨지 않는다(메시지는 두 버전 번호뿐). {@code db migrate}(앱 컨텍스트 없이, 마이그레이터 롤)가 적용하면 뜬다.
 */
class SchemaVersionGuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String BUNDLED = SchemaMigrator.bundledVersion().toString();

    @Test
    void theBundledVersionIsTheHighestMigrationFile() throws Exception {
        try (var files = Files.list(Path.of(System.getProperty("ga.repoRoot")).resolve("disclosure-infra/src/main/resources/db/migration"))) {
            int highest = files.map(p -> p.getFileName().toString()).filter(n -> n.matches("V[0-9]+__.*\\.sql"))
                    .mapToInt(n -> Integer.parseInt(n.substring(1, n.indexOf("__")))).max().orElseThrow();
            assertThat(BUNDLED).isEqualTo(Integer.toString(highest));
        }
    }

    @Test
    void anEmptyDatabaseStopsStartupAndStaysEmpty() throws SQLException {
        String url = database("guard_empty");
        // 빈 DB: 헬스 롤은 V22가 주는 스키마 USAGE부터 없다 — "읽을 수 없음"(42501)으로 멈춘다
        assertThatThrownBy(() -> start(url))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("schema version mismatch: database unreadable (42501), application " + BUNDLED
                        + " — apply migrations with the operator command 'db migrate' before starting");
        assertThat(tables(url)).as("앱 기동이 마이그레이션하지 않는다").isEmpty();
    }

    @Test
    void anOlderSchemaStopsStartupThenDbMigrateLetsItStart() throws SQLException {
        String url = DB.jdbcUrl().replace("/" + PostgresHarness.DATABASE, "/guard_older");
        PostgresHarness.migrate(DB.emptyDatabase("guard_older"), "21");
        // V21까지(Phase 8 1단계까지의 운영 DB와 같은 처지): 헬스 롤 권한이 V22에서 생기므로 역시 42501
        assertThatThrownBy(() -> start(url))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("schema version mismatch: database unreadable (42501), application " + BUNDLED
                        + " — apply migrations with the operator command 'db migrate' before starting");
        assertThat(maxVersion(url)).isEqualTo("21");

        String out = offline("db", "migrate", "--ga.migrator.url=" + url);
        assertThat(out).isEqualTo("DB_MIGRATE applied=1 version=" + BUNDLED + "\n");
        assertThat(offline("db", "migrate", "--ga.migrator.url=" + url)).as("두 번째는 적용 0").isEqualTo("DB_MIGRATE applied=0 version=" + BUNDLED + "\n");
        start(url).close();
    }

    @Test
    void aNewerSchemaAlsoStopsStartup() throws SQLException {
        String url = database("guard_newer");
        offline("db", "migrate", "--ga.migrator.url=" + url);
        try (Connection c = DB.superuserDataSource(name(url)).getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)"
                    + " SELECT max(installed_rank) + 1, '999', 'from a newer release', 'SQL', 'V999__newer.sql', 0, 'test', 0, true FROM flyway_schema_history");
        }
        assertThatThrownBy(() -> start(url)).isInstanceOf(IllegalStateException.class)
                .hasMessage("schema version mismatch: database 999, application " + BUNDLED
                        + " — apply migrations with the operator command 'db migrate' before starting");
    }

    private static String name(String url) {
        return url.substring(url.lastIndexOf('/') + 1).replaceAll("\\?.*", "");
    }

    private static String database(String name) {
        DB.emptyDatabase(name);
        return DB.jdbcUrl().replace("/" + PostgresHarness.DATABASE, "/" + name);
    }

    /** 운영자 CLI(앱 컨텍스트)를 그 DB로 띄운다 — 명령은 DB를 건드리지 않는 secrets init(가드는 명령 전에 돈다). */
    private static org.springframework.context.ConfigurableApplicationContext start(String url) {
        Path secrets;
        try {
            secrets = Files.createTempDirectory("guard-secrets");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new SpringApplicationBuilder(DisclosureApplication.class).run(
                "--spring.profiles.active=cli",
                "--spring.datasource.url=" + url,
                "--ga.health.url=" + url,
                "--ga.tenant-directory.url=" + url,
                "--ga.job-lock.url=" + url,
                "--ga.secrets.dir=" + ApiTestSupport.SECRETS,
                "secrets", "init", "--secrets-dir", secrets.toString());
    }

    private static String offline(String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        OfflineCli.run(args, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String out = buffer.toString(StandardCharsets.UTF_8);
        CliOutputScan.assertClean(out);
        return out;
    }

    private static List<String> tables(String url) throws SQLException {
        return strings(url, "SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND relkind = 'r'");
    }

    private static String maxVersion(String url) throws SQLException {
        return strings(url, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success").getFirst();
    }

    private static List<String> strings(String url, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DB.superuserDataSource(name(url)).getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
