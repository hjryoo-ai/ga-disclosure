package com.ga.disclosure.app;

import com.ga.disclosure.app.api.ApiTestSupport;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ExitCodeGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 ③: CronJob의 진입점 {@code jobs run <KIND> --tenants … [--params k=v]}. 내부 작업 API와 같은 처리기 표·같은 작업 실행기 — 작업 행은 CLI
 * 채널·운영자, 매개변수는 형태대로(정수·불리언·문자열) 처리기가 검사한다. 같은 테넌트×종류가 이미 돌면(다른 Job·다른 클러스터 — CronJob {@code Forbid}가
 * 못 막는 겹침) 그 테넌트만 {@code JOB_BUSY}, 나머지는 돌고 종료 2. 처리기 표 밖 종류는 전용 명령을 알려 준다.
 */
class JobsRunCliIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    private static int exitCode(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && !(t instanceof ExitCodeGenerator)) {
            t = t.getCause();
        }
        assertThat(t).isInstanceOf(ExitCodeGenerator.class);
        return ((ExitCodeGenerator) t).getExitCode();
    }

    static String rows(String t) {
        return DB.<String>asApp(t, c -> SeedData.call(c, "SELECT coalesce(string_agg(kind || ':' || status || ':' || channel || ':' || requested_by || ':'"
                + " || params::text, ',' ORDER BY requested_at), '-') FROM async_job WHERE tenant_id = ?", t));
    }

    @Test
    void aKindOfTheHandlerTableRunsPerTenantThroughTheJobRunner() {
        String a = ContractLinkCliIT.tenant();
        String b = ContractLinkCliIT.tenant();
        String out = ApiTestSupport.cli(ContractLinkCliIT.with(ContractLinkCliIT.storage(), "jobs", "run", "IDEMPOTENCY_PURGE", "--tenants", a + "," + b,
                "--params", "limit=7", "--operator", "cronjob"));
        assertThat(out.lines().filter(l -> l.matches("JOB [0-9a-f-]{36} SUCCEEDED.*"))).hasSize(2);
        for (String t : new String[] {a, b}) {
            assertThat(rows(t)).isEqualTo("IDEMPOTENCY_PURGE:SUCCEEDED:CLI:cronjob:{\"limit\": 7}");
        }
    }

    @Test
    void wrongKindsAndParametersAreRefusedBeforeAnyRow() {
        String t = ContractLinkCliIT.tenant();
        String[] env = ContractLinkCliIT.storage();
        assertThatThrownBy(() -> ApiTestSupport.cli(ContractLinkCliIT.with(env, "jobs", "run", "NOPE", "--tenants", t, "--operator", "cronjob")))
                .hasStackTraceContaining("unknown job kind 'NOPE'");
        assertThatThrownBy(() -> ApiTestSupport.cli(ContractLinkCliIT.with(env, "jobs", "run", "ANCHOR", "--tenants", t, "--operator", "cronjob")))
                .hasStackTraceContaining("ANCHOR does not run through 'jobs run' — use anchor run --tenants all");
        assertThatThrownBy(() -> ApiTestSupport.cli(ContractLinkCliIT.with(env, "jobs", "run", "KEK_REWRAP", "--tenants", t, "--operator", "cronjob")))
                .hasStackTraceContaining("use crypto kek rewrap");
        // 처리기의 검사(HTTP와 같다): 범위 밖 한도, 모르는 매개변수
        assertThatThrownBy(() -> ApiTestSupport.cli(ContractLinkCliIT.with(env, "jobs", "run", "NOTIFY", "--tenants", t, "--params", "limit=0",
                "--operator", "cronjob"))).hasStackTraceContaining("job parameter limit must be 1..");
        assertThatThrownBy(() -> ApiTestSupport.cli(ContractLinkCliIT.with(env, "jobs", "run", "NOTIFY", "--tenants", t, "--params", "asOf=x",
                "--operator", "cronjob"))).hasStackTraceContaining("asOf");
        assertThat(rows(t)).isEqualTo("-");
    }

    @Test
    void anOverlappingRunOfTheSameTenantAndKindIsBusyAndTheOthersStillRun() {
        String busy = ContractLinkCliIT.tenant();
        String free = ContractLinkCliIT.tenant();
        var locks = new com.ga.disclosure.infra.jobs.JobLockGateway(DB.jdbcUrl(), PostgresHarness.JOB_LOCK, PostgresHarness.JOB_LOCK_PASSWORD);
        try (var held = locks.tryAcquire(TenantId.of(busy), JobKind.FLAG_SLA_SWEEP).orElseThrow()) {
            assertThatThrownBy(() -> ApiTestSupport.cli(ContractLinkCliIT.with(ContractLinkCliIT.storage(), "jobs", "run", "FLAG_SLA_SWEEP", "--tenants",
                    busy + "," + free, "--operator", "cronjob"))).satisfies(e -> assertThat(exitCode(e)).isEqualTo(2))
                    .hasStackTraceContaining("already running");
            assertThat(held.stillHeld()).isTrue();
        }
        assertThat(rows(busy)).isEqualTo("-");
        assertThat(rows(free)).startsWith("FLAG_SLA_SWEEP:SUCCEEDED:CLI:cronjob:");
    }
}
