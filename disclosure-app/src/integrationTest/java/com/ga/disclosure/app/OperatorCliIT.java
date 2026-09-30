package com.ga.disclosure.app;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영자 CLI(프로파일 cli)를 실제 애플리케이션으로 실행한다: 데모 시드 → 번들 배포(--tenants all) → 사규 승인 → 활성화 → 대사.
 * 실패(거부)는 애플리케이션 시작 실패(종료 코드 ≠ 0)로 드러난다. 데모 테넌트 ID는 테스트마다 고유 접두를 붙인 시드 파일을 만든다.
 */
class OperatorCliIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));

    private static String run(String... args) {
        List<String> all = new ArrayList<>(List.of(
                "--spring.profiles.active=cli",
                "--spring.datasource.url=" + DB.jdbcUrl(),
                "--spring.flyway.url=" + DB.jdbcUrl(),
                "--ga.tenant-directory.url=" + DB.jdbcUrl()));
        all.addAll(List.of(args));
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(DisclosureApplication.class).run(all.toArray(String[]::new));
            context.close();
            return buffer.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(original);
        }
    }

    private static String bundle(String relative) {
        return ROOT.resolve("contracts/rules/bundles").resolve(relative).toString();
    }

    private static List<String> column(String tenant, String sql) {
        return DB.asApp(tenant, c -> query(c, sql, tenant));
    }

    private static List<String> query(Connection c, String sql, String tenant) throws java.sql.SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    @Test
    void demoSeedFlowEndsWithActiveRulesAndNoDrift() throws Exception {
        String one = SeedData.uniqueTenant("DEMO1");
        String two = SeedData.uniqueTenant("DEMO2");
        String seed = java.nio.file.Files.readString(ROOT.resolve("disclosure-demo/src/main/resources/demo/phase1-seed.json"))
                .replace("\"DEMO1\"", "\"" + one + "\"").replace("\"DEMO2\"", "\"" + two + "\"");
        Path seedFile = java.nio.file.Files.createTempFile("seed", ".json");
        java.nio.file.Files.writeString(seedFile, seed);

        assertThat(run("demo", "seed", "--file", seedFile.toString(), "--operator", "cli-test"))
                .contains("SEED TENANT " + one + " CREATED", "SEED TENANT_RULE " + one + " DEMO1-HOUSE-2026 DRAFT");
        assertThat(run("rules", "distribute", "--bundle", bundle("rules/DISC-2026-07.bundle.json"), "--tenants", "all", "--operator", "cli-test"))
                .contains("DISTRIBUTE " + one + " DISC-2026-07@", "DISTRIBUTE " + two + " DISC-2026-07@");
        run("rules", "distribute", "--bundle", "rules/DISC-2027-01.bundle.json", "--tenants", one + "," + two, "--operator", "cli-test");
        run("rules", "distribute", "--bundle", "templates/STANDARD-v1.bundle.json", "--tenants", one + "," + two, "--operator", "cli-test");
        assertThat(run("rules", "approve", "--tenant", one, "--rule", "DEMO1-HOUSE-2026", "--operator", "cli-test")).contains("APPROVED");
        assertThat(run("rules", "activate", "--as-of", "2026-09-23", "--tenants", one + "," + two, "--operator", "cli-test"))
                .contains("ACTIVATE " + one + " asOf=2026-09-23 retired=[] activated=[DEMO1-HOUSE-2026, DISC-2026-07]");
        assertThat(run("rules", "reconcile", "--tenants", one + "," + two, "--bundles-dir", ROOT.resolve("contracts/rules/bundles").toString(),
                "--operator", "cli-test")).contains("RECONCILE total drift=0");

        assertThat(column(one, "SELECT rule_version_id || ':' || status FROM rule_version WHERE tenant_id = ? ORDER BY 1"))
                .containsExactly("DEMO1-HOUSE-2026:ACTIVE", "DISC-2026-07:ACTIVE", "DISC-2027-01:APPROVED");
        assertThat(column(two, "SELECT rule_version_id || ':' || status FROM rule_version WHERE tenant_id = ? ORDER BY 1"))
                .containsExactly("DISC-2026-07:ACTIVE", "DISC-2027-01:APPROVED");
        assertThat(column(one, "SELECT DISTINCT actor_role FROM audit_log WHERE tenant_id = ?")).containsExactly("OPERATOR");
    }

    @Test
    void rejectedDistributionFailsTheCommand() {
        String bare = SeedData.uniqueTenant("BARE");
        DB.seed(bare, c -> SeedData.tenant(c, bare));
        assertThatThrownBy(() -> run("rules", "distribute", "--bundle", bundle("rules/DISC-2027-01.bundle.json"), "--tenants", bare,
                "--operator", "cli-test"))
                .hasStackTraceContaining("distribution rejected");
    }

    @Test
    void unknownCommandAndMissingOptionFail() {
        assertThatThrownBy(() -> run("rules", "delete", "--operator", "x")).hasStackTraceContaining("unknown command 'rules delete'");
        assertThatThrownBy(() -> run("rules", "approve", "--tenant", "T1", "--operator", "x")).hasStackTraceContaining("missing --rule");
    }

    /**
     * Phase 2: 카탈로그 수입(상품군 → 패널 → 상품, 데모 파일) — 같은 파일 재수입은 NOOP. 로컬 KEK 파일 생성은 한 번만(덮어쓰기 거부).
     * 고객 데이터 키 순환은 두 번째 실행에서 첫 키를 은퇴·파기한다(쓰는 행이 없으므로).
     */
    @Test
    void catalogImportKekInitAndCustomerRekey() throws Exception {
        String tenant = SeedData.uniqueTenant("CLI_CAT");
        DB.seed(tenant, c -> SeedData.tenant(c, tenant));
        Path catalog = ROOT.resolve("disclosure-demo/src/main/resources/demo/catalog");
        for (String file : List.of("product-groups.json", "insurer-panel.json", "products.json")) {
            assertThat(run("catalog", "import", "--tenant", tenant, "--file", catalog.resolve(file).toString(), "--operator", "cli-test"))
                    .contains("CATALOG_IMPORT " + tenant).contains(" IMPORTED ");
        }
        assertThat(run("catalog", "import", "--tenant", tenant, "--file", catalog.resolve("products.json").toString(), "--operator", "cli-test"))
                .contains(" PRODUCTS NOOP ");
        assertThat(column(tenant, "SELECT count(*) FROM product_catalog WHERE tenant_id = ?")).containsExactly("9");
        assertThat(column(tenant, "SELECT DISTINCT source FROM product_catalog WHERE tenant_id = ?")).containsExactly("DEMO_FILE");

        Path kek = java.nio.file.Files.createTempDirectory("cli-kek").resolve("kek.json");
        assertThat(run("crypto", "init-kek", "--file", kek.toString())).contains("KEK_INIT KEK-LOCAL-1");
        assertThat(java.nio.file.Files.getPosixFilePermissions(kek)).extracting(Enum::name)
                .containsExactlyInAnyOrder("OWNER_READ", "OWNER_WRITE");
        assertThatThrownBy(() -> run("crypto", "init-kek", "--file", kek.toString())).hasStackTraceContaining("already exists");

        String first = run("--ga.crypto.local-kek-file=" + kek, "customer", "rekey", "--tenant", tenant, "--operator", "cli-test");
        assertThat(first).contains("REKEY " + tenant + " retired=- ").contains("reencrypted=0 destroyed=[]");
        String second = run("--ga.crypto.local-kek-file=" + kek, "customer", "rekey", "--tenant", tenant, "--operator", "cli-test");
        String firstKey = first.substring(first.indexOf("active=") + 7).split(" ")[0];
        assertThat(second).contains("retired=" + firstKey).contains("destroyed=[" + firstKey + "]");
        assertThat(column(tenant, "SELECT status FROM customer_data_key WHERE tenant_id = ? ORDER BY created_at, status"))
                .containsExactlyInAnyOrder("DESTROYED", "ACTIVE");
    }
}
