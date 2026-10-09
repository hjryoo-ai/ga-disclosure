package com.ga.disclosure.app;

import com.ga.disclosure.app.api.ApiTestSupport;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ExitCodeGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6B 계획 §5 CLI: {@code collection-rates snapshot}은 작업 실행기를 지나고 수치·룰 버전·정의 표기만 출력한다(번호 없음). 같은 (달, 룰 버전)의 재실행은
 * 종료 2 {@code SNAPSHOT_EXISTS}. {@code collection-rates list}는 정의 표기 머리줄과 행.
 */
class CollectionRateCliIT {

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
    void snapshotOnceThenRefuseAndList() {
        String t = ContractLinkCliIT.tenant();
        DB.seed(t, c -> SeedData.contractLink(c, t, SeedData.disclosure(c, t, "COMPLETED", SeedData.hash('a')), "POL-RATE-CLI-1", "2026-09-30"));
        String[] env = ContractLinkCliIT.storage();
        String[] snapshot = ContractLinkCliIT.with(env, "collection-rates", "snapshot", "--tenant", t, "--period", "2026-09", "--operator", "cli-test");
        assertThat(ApiTestSupport.cli(snapshot)).contains("COLLECTION_RATE_SNAPSHOT " + t + " 2026-09 rule=DISC-2026-07 formula=LINKED_COMPLETED_BY_CONTRACT_DATE")
                .contains("rows=2 tenant=1/1 rateBp=10000 INTERNAL_METRIC_NO_REGULATORY_DEFINITION").contains("JOB ").doesNotContain("POL-RATE-CLI-1");
        assertThatThrownBy(() -> ApiTestSupport.cli(snapshot)).satisfies(e -> assertThat(exitCode(e)).isEqualTo(2))
                .hasStackTraceContaining("SNAPSHOT_EXISTS");
        assertThat(ApiTestSupport.cli("collection-rates", "list", "--tenant", t, "--from", "2026-09", "--to", "2026-09", "--operator", "cli-test"))
                .contains("# INTERNAL_METRIC_NO_REGULATORY_DEFINITION (내부 지표 — 규제 정의 없음)")
                .contains("COLLECTION_RATE " + t + " 2026-09 / rule=DISC-2026-07 formula=LINKED_COMPLETED_BY_CONTRACT_DATE 1/1 rateBp=10000")
                .contains("COLLECTION_RATE " + t + " 2026-09 /HQ/B1 ");
    }
}
