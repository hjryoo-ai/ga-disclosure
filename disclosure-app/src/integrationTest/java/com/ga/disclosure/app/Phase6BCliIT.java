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
 * 6B 지시문 §9 CLI의 준법 큐: {@code flags list}(상태·유형 필터, ID·유형·상태·열린 시각만)와 {@code flags resolve}(HTTP와 같은 유스케이스 — 수동 해소 코드,
 * {@code CHAIN_BROKEN}은 근거 작업 없이 거부, 근거가 MATCH 작업이 아니면 거부, 업무 거부는 종료 2). 출력에 개인정보가 없다.
 */
class Phase6BCliIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    private static int exitCode(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && !(t instanceof ExitCodeGenerator)) {
            t = t.getCause();
        }
        assertThat(t).isInstanceOf(ExitCodeGenerator.class);
        return ((ExitCodeGenerator) t).getExitCode();
    }

    static UUID flag(String t, String type) {
        UUID id = UUID.randomUUID();
        DB.seed(t, c -> SeedData.exec(c, """
                INSERT INTO compliance_flag (tenant_id, flag_id, type, severity, raised_at, target_kind, target_id)
                VALUES (?, ?, ?, 'HIGH', clock_timestamp(), 'TENANT', ?)""", t, id, type, t));
        return id;
    }

    @Test
    void flagsAreListedAndResolvedThroughTheSameRules() {
        String t = ContractLinkCliIT.tenant();
        UUID expired = flag(t, "SIGN_EXPIRED");
        UUID chain = flag(t, "CHAIN_BROKEN");

        String open = ApiTestSupport.cli("flags", "list", "--tenant", t, "--status", "OPEN", "--operator", "cli-test");
        assertThat(open).contains("FLAG " + t + " " + expired + " SIGN_EXPIRED OPEN raisedAt=").contains("FLAG " + t + " " + chain + " CHAIN_BROKEN OPEN")
                .contains("FLAGS " + t + " 2");
        assertThat(ApiTestSupport.cli("flags", "list", "--tenant", t, "--type", "CHAIN_BROKEN", "--operator", "cli-test"))
                .contains(chain.toString()).doesNotContain(expired.toString());

        assertThat(ApiTestSupport.cli("flags", "resolve", "--tenant", t, "--id", expired.toString(), "--code", "REISSUED", "--operator", "cli-test"))
                .contains("FLAG_RESOLVE " + t + " " + expired + " SIGN_EXPIRED RESOLVED REISSUED");
        // CHAIN_BROKEN: 근거 없음 → EVIDENCE_REQUIRED, MATCH가 아닌(없는) 작업 → CHAIN_EVIDENCE_REJECTED — 둘 다 종료 2, 플래그는 열린 채
        for (String[] attempt : new String[][] {{}, {"--verify-job", UUID.randomUUID().toString()}}) {
            String[] args = new String[] {"flags", "resolve", "--tenant", t, "--id", chain.toString(), "--code", "VERIFIED_MATCH", "--operator", "cli-test"};
            String[] all = java.util.Arrays.copyOf(args, args.length + attempt.length);
            System.arraycopy(attempt, 0, all, args.length, attempt.length);
            assertThatThrownBy(() -> ApiTestSupport.cli(all)).satisfies(e -> assertThat(exitCode(e)).isEqualTo(2))
                    .hasStackTraceContaining(attempt.length == 0 ? "EVIDENCE_REQUIRED" : "CHAIN_EVIDENCE_REJECTED");
        }
        String resolved = ApiTestSupport.cli("flags", "list", "--tenant", t, "--status", "RESOLVED", "--operator", "cli-test");
        assertThat(resolved).contains(expired.toString()).doesNotContain(chain.toString()).contains("FLAGS " + t + " 1");
        CliOutputScan.assertClean(open + resolved);
    }
}
