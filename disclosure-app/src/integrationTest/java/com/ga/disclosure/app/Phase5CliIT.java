package com.ga.disclosure.app;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeaweedHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 CLI를 실제 애플리케이션으로(5 계획 §8, 데모 §8.8·§8.9).
 * <ul>
 *   <li>앵커(스텁 TSA, 키는 임시 디렉터리 — 저장소 밖) → 재실행 NOOP → 미래 날짜 거부 → 영수증 내보내기 → 증거 패키지 → {@code verify package}(영수증
 *       없음·있음 둘 다 0, 손상 ZIP 3) → {@code verify tenant} 0.</li>
 *   <li>짧은 보존 테넌트(데모 번들 DISC-DEMO-SHORT만 배포): 데모 프로파일 시계 오프셋으로 봉인·무효 → 실제 시계 재적용({@code RETENTION_ALREADY_ELAPSED})
 *       → 보류 1건 → 파기(1건 파기·1건 HOLD) → {@code verify tenant} 0 → 재실행 파기 0 → 해제 → 파기.</li>
 * </ul>
 */
class Phase5CliIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));
    private static final Path DEMO = ROOT.resolve("disclosure-demo/src/main/resources");

    private static String run(String... args) {
        List<String> all = new ArrayList<>(List.of(
                "--spring.profiles.active=cli",
                "--spring.datasource.url=" + DB.jdbcUrl(),
                "--spring.flyway.url=" + DB.jdbcUrl(),
                "--ga.tenant-directory.url=" + DB.jdbcUrl(),
                "--ga.job-lock.url=" + DB.jdbcUrl()));
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
            CliOutputScan.assertClean(buffer.toString(StandardCharsets.UTF_8));   // 실패 경로의 출력도(6A §9.3)
        }
    }

    private static String[] with(String[] prefix, String... args) {
        String[] all = Arrays.copyOf(prefix, prefix.length + args.length);
        System.arraycopy(args, 0, all, prefix.length, args.length);
        return all;
    }

    private static int exitCode(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && !(t instanceof ExitCodeGenerator)) {
            t = t.getCause();
        }
        assertThat(t).isInstanceOf(ExitCodeGenerator.class);
        return ((ExitCodeGenerator) t).getExitCode();
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

    private static String idOf(String output, String head) {
        return output.lines().filter(l -> l.startsWith(head)).findFirst().orElseThrow(() -> new AssertionError(head + " not in output"))
                .replaceFirst("^.* (?:id|existing)=([0-9a-f-]{36}).*$", "$1");
    }

    /** 테넌트·식별 연결·번들·카탈로그·고객까지. 저장소·KEK·엔진 스텁·스텁 TSA 인자를 돌려준다. */
    private static String[] prepare(String tenant, List<String> bundles, Path tmp) throws Exception {
        DB.seed(tenant, c -> {
            SeedData.tenant(c, tenant);
            SeedData.identityLink(c, tenant, "demo-agent", "DEMO-AGENT-1", "AGENT");
            SeedData.identityLink(c, tenant, "demo-manager", "DEMO-MGR-1", "MANAGER");
        });
        SeaweedHarness s3 = SeaweedHarness.get();
        Path kek = tmp.resolve("kek.json");
        run("crypto", "init-kek", "--file", kek.toString());
        String[] env = {"--ga.crypto.local-kek-file=" + kek, "--ga.storage.s3.endpoint=" + s3.endpoint(), "--ga.storage.s3.bucket=" + s3.freshBucket(),
                "--ga.storage.s3.access-key-id=" + SeaweedHarness.ACCESS_KEY, "--ga.storage.s3.secret-access-key=" + SeaweedHarness.SECRET_KEY,
                "--ga.engine.mode=stub", "--ga.engine.stub-table=" + DEMO.resolve("demo/engine-table.json"),
                "--ga.tsa.mode=stub", "--ga.tsa.stub.key-store=" + tmp.resolve("home/tsa-stub.p12"), "--ga.tsa.trust-pem=" + tmp.resolve("tsa-trust.pem")};
        for (String b : bundles) {
            run("rules", "distribute", "--bundle", b, "--tenants", tenant, "--operator", "cli-test");
        }
        run("rules", "activate", "--as-of", "2026-09-23", "--tenants", tenant, "--operator", "cli-test");
        for (String file : List.of("product-groups.json", "insurer-panel.json", "products.json")) {
            run("catalog", "import", "--tenant", tenant, "--file", DEMO.resolve("demo/catalog").resolve(file).toString(), "--operator", "cli-test");
        }
        run(with(env, "customer", "import", "--tenant", tenant, "--file", DEMO.resolve("customers.json").toString(), "--operator", "cli-test"));
        return env;
    }

    @Test
    void anchorReceiptAndBothVerifyCommands() throws Exception {
        String tenant = SeedData.uniqueTenant("CLI_ANC");
        Path tmp = Files.createTempDirectory("cli-anchor");
        Path contracts = ROOT.resolve("contracts/rules/bundles");
        String[] env = prepare(tenant, List.of(contracts.resolve("rules/DISC-2026-07.bundle.json").toString(),
                contracts.resolve("templates/STANDARD-v1.bundle.json").toString()), tmp);
        run(with(env, "demo", "disclosures", "--tenant", tenant, "--file", DEMO.resolve("demo/disclosures-demo2.json").toString(), "--operator",
                "cli-test"));
        String signed = run(with(env, "demo", "signatures", "--tenant", tenant, "--file", DEMO.resolve("demo/signatures-demo2.json").toString()));
        String id = idOf(signed, "DEMO_SIGN " + tenant + " A-4-SCAN");

        assertThat(run(with(env, "anchor", "run", "--tenants", tenant, "--operator", "cli-test")))
                .contains("ANCHOR_RUN date=", "created=[" + tenant + "]", "receipts=1", "  BATCH ");
        assertThat(Files.readString(tmp.resolve("tsa-trust.pem"))).startsWith("-----BEGIN CERTIFICATE-----");
        assertThat(Files.exists(tmp.resolve("home/tsa-stub.p12"))).isTrue();
        assertThat(run(with(env, "anchor", "run", "--tenants", tenant, "--operator", "cli-test")))
                .contains("created=[]", "unchanged=[" + tenant + "]", "batches=0", "receipts=0");
        // 5 수용심사 R1: 오늘(KST)이 아닌 날짜는 미래도 소급도 DATE_NOT_TODAY — 앵커는 늘지 않는다
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (LocalDate other : List.of(today.plusDays(1), today.minusDays(1))) {
            assertThatThrownBy(() -> run(with(env, "anchor", "run", "--date", other.toString(), "--tenants", tenant, "--operator", "cli-test")))
                    .hasStackTraceContaining("anchor run finished with 1 failure(s)").satisfies(e -> assertThat(exitCode(e)).isEqualTo(2));
        }
        assertThat(column(tenant, "SELECT count(*) FROM anchor WHERE tenant_id = ?")).containsExactly("1");

        Path receipt = tmp.resolve("receipt.json");
        assertThat(run(with(env, "anchor", "receipt", "export", "--tenant", tenant, "--id", id, "--out", receipt.toString(), "--operator", "auditor-1")))
                .contains("RECEIPT_EXPORT " + tenant + " " + id + " covering=1@");
        Path zip = tmp.resolve("evidence.zip");
        run(with(env, "artifacts", "get", "--tenant", tenant, "--id", id, "--kind", "EVIDENCE_ZIP", "--out", zip.toString(), "--operator", "auditor-1"));

        assertThat(run(with(env, "verify", "package", "--package", zip.toString()))).contains("VERIFY_PACKAGE MATCH findings=0");
        String withReceipt = run(with(env, "verify", "package", "--package", zip.toString(), "--receipt", receipt.toString(), "--tsa-trust",
                tmp.resolve("tsa-trust.pem").toString(), "--report", tmp.resolve("report.json").toString()));
        assertThat(withReceipt).contains("VERIFY_PACKAGE MATCH findings=0", "  STATEMENT ");
        assertThat(Files.readString(tmp.resolve("report.json"))).contains("\"existedBefore\":\"");
        Path broken = Files.write(tmp.resolve("broken.zip"), "not a zip".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> run(with(env, "verify", "package", "--package", broken.toString())))
                .satisfies(e -> assertThat(exitCode(e)).isEqualTo(3));

        assertThat(run(with(env, "verify", "tenant", "--tenants", tenant, "--tsa-trust", tmp.resolve("tsa-trust.pem").toString(), "--operator",
                "auditor-1"))).contains("VERIFY_TENANT " + tenant + " MATCH findings=0");
        assertThat(column(tenant, "SELECT action FROM audit_log WHERE tenant_id = ? AND action IN ('ANCHOR_RECEIPT_EXPORTED', 'VERIFY_RUN') ORDER BY seq"))
                .containsExactly("ANCHOR_RECEIPT_EXPORTED", "VERIFY_RUN");
    }

    @Test
    void shortRetentionTenantIsDestroyedExceptTheHeldDisclosure() throws Exception {
        String tenant = SeedData.uniqueTenant("CLI_SHORT");
        Path tmp = Files.createTempDirectory("cli-short");
        Path demoBundles = DEMO.resolve("demo/bundles");
        String[] env = prepare(tenant, List.of(demoBundles.resolve("rules/DISC-DEMO-SHORT.bundle.json").toString(),
                ROOT.resolve("contracts/rules/bundles/templates/STANDARD-v1.bundle.json").toString()), tmp);
        assertThat(run("rules", "reconcile", "--tenants", tenant, "--bundles-dir", ROOT.resolve("contracts/rules/bundles") + "," + demoBundles,
                "--operator", "cli-test")).contains("RECONCILE total drift=0");

        // 데모 프로파일 + 시계 오프셋(과거)으로 봉인·무효 — 보존기한(0년 1일)이 실제 시각으로는 이미 지났다
        String[] past = with(env, "--spring.profiles.active=cli,demo", "--ga.demo.clock-offset=-P5D");
        String seeded = run(with(past, "demo", "disclosures", "--tenant", tenant, "--file", DEMO.resolve("demo/disclosures-demo3.json").toString(),
                "--operator", "cli-test"));
        assertThat(seeded).contains("A-6-SHORT seal -> SEALED", "A-6-SHORT void -> VOID", "A-7-HOLD void -> VOID");
        String shortId = idOf(seeded, "DEMO_DISCLOSURE " + tenant + " A-6-SHORT");
        String heldId = idOf(seeded, "DEMO_DISCLOSURE " + tenant + " A-7-HOLD");

        assertThat(run(with(env, "artifacts", "reconcile", "--tenants", tenant, "--operator", "cli-test"))).contains("ARTIFACT_RECONCILE " + tenant,
                "failed=0");
        assertThat(column(tenant, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'ARTIFACT_RETAIN'"
                + " AND detail ->> 'reason' = 'RETENTION_ALREADY_ELAPSED'").getFirst()).isNotEqualTo("0");

        assertThat(run(with(env, "legal-hold", "place", "--tenant", tenant, "--id", heldId, "--reason-code", "LITIGATION", "--operator", "compliance-1")))
                .contains("LEGAL_HOLD_PLACE " + tenant + " disclosure=" + heldId + " hold=", "storageHold=");
        assertThat(run(with(env, "legal-hold", "place", "--tenant", tenant, "--id", heldId, "--reason-code", "LITIGATION", "--if-absent", "yes",
                "--operator", "compliance-1"))).contains("NOOP (an active hold exists)");
        assertThatThrownBy(() -> run(with(env, "legal-hold", "place", "--tenant", tenant, "--id", heldId, "--reason-code", "LITIGATION", "--operator",
                "compliance-1"))).hasStackTraceContaining("ALREADY_HELD").satisfies(e -> assertThat(exitCode(e)).isEqualTo(2));

        assertThat(run(with(env, "retention", "destroy", "--tenants", tenant, "--dry-run", "yes", "--operator", "cli-test")))
                .contains("DRY_RUN", "wouldDestroy=1", "  WOULD_DESTROY " + shortId);
        Path reports = tmp.resolve("reports");
        String destroyed = run(with(env, "retention", "destroy", "--tenants", tenant, "--report-dir", reports.toString(), "--operator", "cli-test"));
        assertThat(destroyed).contains("destroyed=1", "  DESTROYED " + shortId, "  SKIPPED " + heldId + " HOLD");
        assertThat(Files.readString(reports.resolve("destruction-" + tenant + ".json"))).contains("\"reportVersion\":1");
        // 6A 계획 §6.2: 배치 명령은 작업 실행기를 지난다 — JOB 줄, 작업 목록·한 건, 암호화 보고서의 평문이 --report-dir 파일과 같은 바이트
        java.util.regex.Matcher job = java.util.regex.Pattern.compile("JOB ([0-9a-f-]{36}) SUCCEEDED report=([0-9a-f]{64})").matcher(destroyed);
        assertThat(job.find()).as(destroyed).isTrue();
        String jobId = job.group(1);
        assertThat(run(with(env, "jobs", "list", "--tenant", tenant, "--operator", "auditor-1")))
                .contains("JOB " + jobId + " DESTROY SUCCEEDED", "by=cli-test via=CLI", "DESTROY_DRY_RUN SUCCEEDED");
        assertThat(run(with(env, "jobs", "show", "--tenant", tenant, "--id", jobId, "--operator", "auditor-1"))).contains("  params {");
        Path jobReport = tmp.resolve("job-report.json");
        assertThat(run(with(env, "jobs", "report", "--tenant", tenant, "--id", jobId, "--out", jobReport.toString(), "--operator", "auditor-1")))
                .contains("JOB_REPORT " + jobId + " sha256=" + job.group(2));
        assertThat(Files.readAllBytes(jobReport)).isEqualTo(Files.readAllBytes(reports.resolve("destruction-" + tenant + ".json")));
        assertThat(column(tenant, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'JOB_REPORT_VIEW'")).containsExactly("1");
        assertThatThrownBy(() -> run(with(env, "jobs", "show", "--tenant", tenant, "--id", java.util.UUID.randomUUID().toString(), "--operator",
                "auditor-1"))).hasStackTraceContaining("AuthorizationDenied");
        assertThat(run(with(env, "verify", "tenant", "--tenants", tenant, "--operator", "auditor-1"))).contains("VERIFY_TENANT " + tenant + " MATCH");
        assertThat(run(with(env, "retention", "destroy", "--tenants", tenant, "--operator", "cli-test"))).contains("destroyed=0");

        String hold = column(tenant, "SELECT hold_id::text FROM legal_hold WHERE tenant_id = ? AND released_at IS NULL").getFirst();
        assertThat(run(with(env, "legal-hold", "release", "--tenant", tenant, "--hold", hold, "--reason-code", "CASE_CLOSED", "--operator",
                "compliance-2"))).contains("LEGAL_HOLD_RELEASE " + tenant + " hold=" + hold);
        assertThat(run(with(env, "retention", "destroy", "--tenants", tenant, "--operator", "cli-test"))).contains("  DESTROYED " + heldId);
        assertThat(column(tenant, "SELECT count(*) FROM disclosure WHERE tenant_id = ? AND destroyed_at IS NOT NULL")).containsExactly("2");
    }

    /**
     * 6A 계획 §6.2: 잠긴 테넌트는 그 테넌트만 실패한다 — 다른 프로세스가 같은 잠금 키를 쥐고 있으면 그 테넌트에는 작업 행이 생기지 않고, 나머지 테넌트는 돌며,
     * 명령은 종료 코드 2로 끝난다.
     */
    @Test
    void aLockedTenantIsBusyWhileTheOthersRun() throws Exception {
        String busy = SeedData.uniqueTenant("BUSY");
        String free = SeedData.uniqueTenant("FREE");
        DB.seed(busy, c -> SeedData.tenant(c, busy));
        DB.seed(free, c -> SeedData.tenant(c, free));
        SeaweedHarness s3 = SeaweedHarness.get();
        String[] noKek = {"--ga.storage.s3.endpoint=" + s3.endpoint(), "--ga.storage.s3.bucket=" + s3.freshBucket(),
                "--ga.storage.s3.access-key-id=" + SeaweedHarness.ACCESS_KEY, "--ga.storage.s3.secret-access-key=" + SeaweedHarness.SECRET_KEY};
        Path kek = Files.createTempDirectory("cli-busy").resolve("kek.json");
        run("crypto", "init-kek", "--file", kek.toString());
        String[] storage = with(noKek, "--ga.crypto.local-kek-file=" + kek);
        com.ga.disclosure.infra.jobs.JobLockGateway locks = new com.ga.disclosure.infra.jobs.JobLockGateway(DB.jdbcUrl(), PostgresHarness.JOB_LOCK,
                PostgresHarness.JOB_LOCK_PASSWORD);
        try (var held = locks.tryAcquire(com.ga.platform.core.tenant.TenantId.of(busy), com.ga.disclosure.workflow.job.JobKind.RECONCILE).orElseThrow()) {
            assertThatThrownBy(() -> run(with(storage, "artifacts", "reconcile", "--tenants", busy + "," + free, "--operator", "cli-test")))
                    .hasStackTraceContaining("already running").satisfies(e -> assertThat(exitCode(e)).isEqualTo(2));
            assertThat(held.stillHeld()).isTrue();
        }
        assertThat(column(busy, "SELECT count(*) FROM async_job WHERE tenant_id = ?")).containsExactly("0");
        assertThat(column(free, "SELECT status || ':' || kind || ':' || channel FROM async_job WHERE tenant_id = ?"))
                .containsExactly("SUCCEEDED:RECONCILE:CLI");

        // 본체는 끝났지만 보고서를 저장하지 못한 작업(여기서는 KEK 없음)도 종료 코드 2 — 작업은 FAILED이고 보고서가 없다
        assertThatThrownBy(() -> run(with(noKek, "artifacts", "reconcile", "--tenants", free, "--operator", "cli-test")))
                .hasStackTraceContaining("REPORT_STORE_FAILED").satisfies(e -> assertThat(exitCode(e)).isEqualTo(2));
        assertThat(column(free, "SELECT status || ':' || coalesce(error_code, '-') FROM async_job WHERE tenant_id = ? ORDER BY requested_at, status"))
                .contains("FAILED:REPORT_STORE_FAILED");
    }
}
