package com.ga.disclosure.app;

import com.ga.disclosure.app.api.ApiTestSupport;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6B 지시문 §9 CLI {@code gate check}: 요청은 파일로만(번호를 인자·셸 기록에 남기지 않는다), 출력은 판정·사유·확인서 번호·대기 역할·룰 버전뿐(요청 번호를
 * 되풀이하지 않는다). 같은 유스케이스라 운영자 대리 실행도 감사 1행.
 */
class GateCliIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String CUSTOMER = "CR-" + "4".repeat(32);

    @Test
    void checkReadsAFileAndPrintsTheDecisionOnly() throws Exception {
        String t = ContractLinkCliIT.tenant();
        UUID[] id = new UUID[1];
        DB.seed(t, c -> {
            SeedData.dataKey(c, t, SeedData.SEED_KEY_ID);
            SeedData.customer(c, t, CUSTOMER);
            id[0] = SeedData.disclosure(c, t, "COMPLETED", SeedData.hash('a'), "DISC-2026-07", CUSTOMER);
        });
        try (Connection c = DB.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            SeedData.exec(c, "UPDATE disclosure SET application_no = 'APP-CLI-SECRET' WHERE tenant_id = ? AND disclosure_id = ?", t, id[0]);
            c.commit();
        }
        String no = DB.asApp(t, c -> SeedData.call(c, "SELECT disclosure_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", t, id[0]));
        Path dir = Files.createTempDirectory("gate-cli");
        Path request = dir.resolve("gate.json");
        Files.writeString(request, "{\"applicationNo\":\"APP-CLI-SECRET\",\"customerRef\":\"" + CUSTOMER + "\"}", StandardCharsets.UTF_8);
        assertThat(ApiTestSupport.cli("gate", "check", "--tenant", t, "--file", request.toString(), "--operator", "cli-test"))
                .contains("GATE " + t + " ALLOWED SATISFIED disclosureNo=" + no + " pending=[] rule=DISC-2026-07").doesNotContain("APP-CLI-SECRET");
        assertThat(DB.<String>asApp(t, c -> SeedData.call(c, "SELECT count(*)::text FROM audit_log WHERE tenant_id = ? AND action = 'GATE_DECISION'", t)))
                .isEqualTo("1");

        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{\"applicationNo\":\"APP-CLI-SECRET\",\"policyNo\":\"POL-CLI\",\"customerRef\":\"" + CUSTOMER + "\"}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ApiTestSupport.cli("gate", "check", "--tenant", t, "--file", bad.toString(), "--operator", "cli-test"))
                .hasStackTraceContaining("gate request field is malformed: identifier")
                .satisfies(e -> assertThat(ContractLinkCliIT.stack(e)).doesNotContain("APP-CLI-SECRET"));
    }
}
