package com.ga.disclosure.app;

import com.ga.disclosure.app.api.ApiTestSupport;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ExitCodeGenerator;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6B 계획 §8 CLI: {@code retention recompute}는 작업 실행기를 지나고 기본은 dry-run({@code WOULD_EXTEND}), {@code --apply yes}면 연장하고
 * 재실행은 연장 0. 쓸 수 없는 룰 버전은 {@code REJECTED RULE_VERSION_NOT_USABLE}·종료 2.
 */
class RetentionRecomputeCliIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    private static int exitCode(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && !(t instanceof ExitCodeGenerator)) {
            t = t.getCause();
        }
        assertThat(t).isInstanceOf(ExitCodeGenerator.class);
        return ((ExitCodeGenerator) t).getExitCode();
    }

    @Test
    void dryRunThenApplyThenNothingLeftAndAnUnusableVersionExitsTwo() {
        String t = ContractLinkCliIT.tenant();
        UUID[] completed = new UUID[1];
        DB.seed(t, c -> {
            completed[0] = SeedData.disclosure(c, t, "COMPLETED", SeedData.hash('a'));
            SeedData.disclosure(c, t, "SEALED", SeedData.hash('b'));
        });
        String no = DB.asApp(t, c -> SeedData.call(c, "SELECT disclosure_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", t, completed[0]));
        String[] env = ContractLinkCliIT.storage();

        assertThat(ApiTestSupport.cli(ContractLinkCliIT.with(env, "retention", "recompute", "--tenants", t, "--rule-version", "DISC-2026-07",
                "--operator", "cli-test")))
                .contains("RETENTION_RECOMPUTE " + t + " rule=DISC-2026-07 DRY_RUN extended=1 unchanged=1 destroyedExcluded=0 relockPending=0")
                .contains("  WOULD_EXTEND " + no + " 2031-09-23 -> 2031-09-24").contains("JOB ");
        assertThat(ApiTestSupport.cli(ContractLinkCliIT.with(env, "retention", "recompute", "--tenants", t, "--rule-version", "DISC-2026-07",
                "--apply", "yes", "--operator", "cli-test")))
                .contains("RETENTION_RECOMPUTE " + t + " rule=DISC-2026-07 APPLY extended=1 unchanged=1")
                .contains("  EXTENDED " + no + " 2031-09-23 -> 2031-09-24");
        assertThat(ApiTestSupport.cli(ContractLinkCliIT.with(env, "retention", "recompute", "--tenants", t, "--rule-version", "DISC-2026-07",
                "--apply", "yes", "--operator", "cli-test")))
                .contains("RETENTION_RECOMPUTE " + t + " rule=DISC-2026-07 APPLY extended=0 unchanged=2").doesNotContain("EXTENDED " + no);
        assertThatThrownBy(() -> ApiTestSupport.cli(ContractLinkCliIT.with(env, "retention", "recompute", "--tenants", t, "--rule-version",
                "DISC-NO-SUCH", "--apply", "yes", "--operator", "cli-test")))
                .satisfies(e -> assertThat(exitCode(e)).isEqualTo(2));
    }
}
