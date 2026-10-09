package com.ga.disclosure.app;

import com.ga.disclosure.app.api.ApiTestSupport;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ExitCodeGenerator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6B 계획 §4 CLI: {@code contract-links import}는 파일(CSV·JSON)로만 받고 결과별 수만 출력한다 — 증권·청약 번호는 인자·출력 어디에도 없다. 형식 위반은
 * 종료 3(위치·규칙 이름만). {@code contract-links purge-unmatched}는 룰 값이 null이면 0건.
 */
class ContractLinkCliIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));

    static String tenant() {
        String t = SeedData.uniqueTenant("CLI_LINK");
        DB.seed(t, c -> SeedData.tenant(c, t));
        ApiTestSupport.cli("rules", "distribute", "--bundle", ROOT.resolve("contracts/rules/bundles/rules/DISC-2026-07.bundle.json").toString(),
                "--tenants", t, "--operator", "cli-test");
        ApiTestSupport.cli("rules", "activate", "--as-of", "2026-09-23", "--tenants", t, "--operator", "cli-test");
        return t;
    }

    /** 작업 보고서는 저장소에 암호화돼 올라간다 — 격리된 버킷. */
    static String[] storage() {
        com.ga.disclosure.infra.testing.SeaweedHarness s3 = com.ga.disclosure.infra.testing.SeaweedHarness.get();
        return new String[]{"--ga.storage.s3.endpoint=" + s3.endpoint(), "--ga.storage.s3.bucket=" + s3.freshBucket(),
                "--ga.storage.s3.access-key-id=" + com.ga.disclosure.infra.testing.SeaweedHarness.ACCESS_KEY,
                "--ga.storage.s3.secret-access-key=" + com.ga.disclosure.infra.testing.SeaweedHarness.SECRET_KEY};
    }

    static String[] with(String[] env, String... args) {
        String[] all = java.util.Arrays.copyOf(env, env.length + args.length);
        System.arraycopy(args, 0, all, env.length, args.length);
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

    @Test
    void importFromFilesPrintsCountsOnly() throws Exception {
        String t = tenant();
        String[] env = storage();
        Path dir = Files.createTempDirectory("contract-links");
        Path csv = dir.resolve("feed.csv");
        Files.writeString(csv, "policyNo,applicationNo,contractDate,insurerCode,customerRef,productKey\nPOL-CLI-SECRET-1,,2026-09-30,INS-A,,\n",
                StandardCharsets.UTF_8);
        String out = ApiTestSupport.cli(with(env, "contract-links", "import", "--tenant", t, "--file", csv.toString(), "--format", "csv", "--source", "INS_FEED_A",
                "--batch-id", "cli-1", "--operator", "cli-test"));
        assertThat(out).contains("CONTRACT_LINKS " + t + " source=INS_FEED_A batch=cli-1 items=1").contains("UNMATCHED=1").contains("LINKED=0")
                .contains("JOB ").doesNotContain("POL-CLI-SECRET-1");
        Path json = dir.resolve("feed.json");
        Files.writeString(json, "{\"schemaVersion\":1,\"source\":\"INS_FEED_A\",\"batchId\":\"cli-2\",\"items\":[{\"policyNo\":\"POL-CLI-SECRET-2\","
                + "\"contractDate\":\"2026-09-30\",\"insurerCode\":\"INS-A\"}]}", StandardCharsets.UTF_8);
        assertThat(ApiTestSupport.cli(with(env, "contract-links", "import", "--tenant", t, "--file", json.toString(), "--format", "json", "--operator", "cli-test")))
                .contains("UNMATCHED=1").doesNotContain("POL-CLI-SECRET-2");

        Path bad = dir.resolve("bad.csv");
        Files.writeString(bad, "policyNo,applicationNo,contractDate,insurerCode,customerRef,productKey\nPOL-CLI-SECRET-3,,2026-09-30,INS_A,,\n",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ApiTestSupport.cli(with(env, "contract-links", "import", "--tenant", t, "--file", bad.toString(), "--format", "csv",
                "--source", "INS_FEED_A", "--batch-id", "cli-3", "--operator", "cli-test")))
                .satisfies(e -> assertThat(exitCode(e)).isEqualTo(3)).hasStackTraceContaining("contract-link batch rejected")
                .satisfies(e -> assertThat(stack(e)).doesNotContain("POL-CLI-SECRET-3"));

        assertThat(ApiTestSupport.cli(with(env, "contract-links", "purge-unmatched", "--tenant", t, "--operator", "cli-test")))
                .contains("CONTRACT_LINKS_PURGE " + t + " purged=0 retentionDays=-");
    }

    static String stack(Throwable e) {
        java.io.StringWriter w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }
}
