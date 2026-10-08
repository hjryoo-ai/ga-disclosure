package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.compliance.rules.RuleActivationJob;
import com.ga.disclosure.compliance.rules.RuleDistributionService;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2(6B 지시문 §2, 계획 §7): {@code CHAIN_BROKEN}은 {@code verify tenant} MATCH 없이 닫히지 않는다 — 근거 없음·플래그 이전에 시작한 MATCH·플래그
 * 뒤의 MISMATCH·다른 종류·다른 테넌트의 작업은 422(세부는 감사에만), 플래그 뒤에 시작해 MATCH로 끝난 {@code VERIFY_TENANT} 작업으로 해소되고 감사에 근거가
 * 남는다. 재개는 없다(409). 실제 작업 경로(HTTP 제출 → 비동기 실행 → 보고서 해시 → {@code VERIFY_RUN} 감사)를 그대로 쓴다.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChainBrokenResolutionIT {

    static final String COMPLIANCE = "compliance-1";
    static final String MANAGER = "manager-1";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    RuleDistributionService distribution;

    @Autowired
    RuleActivationJob activation;

    String tenant() {
        String t = SeedData.uniqueTenant("CHAIN");
        DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.roleLink(c, t, COMPLIANCE, "COMPLIANCE");
            SeedData.orgLink(c, t, MANAGER, null, "MANAGER", "/HQ/B1");
        });
        ApiTestSupport.activateRules(distribution, activation, t, null, body -> {
        });
        return t;
    }

    ApiTestSupport.Response post(String tenant, String subject, String path, String json) {
        return ApiTestSupport.post(port, path, TestJwts.token(tenant, subject), json, Map.of("Idempotency-Key", "it-" + UUID.randomUUID()));
    }

    /** 준법이 VERIFY_TENANT를 제출하고 끝날 때까지 기다린다. 작업 ID와 보고서의 결과. */
    record Run(UUID jobId, String result) {
    }

    Run verify(String t) throws InterruptedException {
        ApiTestSupport.Response submitted = post(t, COMPLIANCE, "/api/v1/jobs/VERIFY_TENANT", "{}");
        assertThat(submitted.status()).as(submitted.text()).isEqualTo(202);
        String location = submitted.headers().get("location").replace("/internal/v1/", "/api/v1/");
        String status = "";
        for (long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos(); System.nanoTime() < deadline; Thread.sleep(50)) {
            status = Canonicalizer.parseStrict(ApiTestSupport.get(port, location, TestJwts.token(t, COMPLIANCE)).text()).get("status").asString();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                break;
            }
        }
        assertThat(status).isEqualTo("SUCCEEDED");
        JsonNode report = Canonicalizer.parseStrict(ApiTestSupport.get(port, location + "/report", TestJwts.token(t, COMPLIANCE)).text());
        return new Run(UUID.fromString(location.substring(location.lastIndexOf('/') + 1)), report.get("result").asString());
    }

    static UUID chainFlag(String t, String targetId) {
        UUID id = UUID.randomUUID();
        DB.seed(t, c -> SeedData.exec(c, """
                INSERT INTO compliance_flag (tenant_id, flag_id, type, severity, raised_at, target_kind, target_id)
                VALUES (?, ?, 'CHAIN_BROKEN', 'HIGH', clock_timestamp(), 'TENANT', ?)""", t, id, targetId));
        return id;
    }

    static String resolveBody(String code, UUID jobOrNull) {
        return jobOrNull == null ? "{\"resolutionCode\":\"" + code + "\"}"
                : "{\"resolutionCode\":\"" + code + "\",\"evidence\":{\"verifyRunJobId\":\"" + jobOrNull + "\"}}";
    }

    static String rejection(ApiTestSupport.Response r) {
        return Canonicalizer.parseStrict(r.text()).at("/details/rejections/0/code").asString();
    }

    /** 감사 체인의 첫 행 detail을 트리거 밖에서 바꾸고(되돌릴 원문을 돌려준다) — 검증이 MISMATCH를 내게 한다. */
    static String tamper(String t, String detailOrNull) {
        try (Connection c = DB.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            String before;
            try (var ps = c.prepareStatement("SELECT detail::text FROM audit_log WHERE tenant_id = ? ORDER BY seq LIMIT 1")) {
                ps.setString(1, t);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    before = rs.getString(1);
                }
            }
            try (var ps = c.prepareStatement("UPDATE audit_log SET detail = CAST(? AS jsonb) WHERE tenant_id = ? AND seq = (SELECT min(seq) FROM audit_log WHERE tenant_id = ?)")) {
                ps.setString(1, detailOrNull == null ? "{\"tampered\": true}" : detailOrNull);
                ps.setString(2, t);
                ps.setString(3, t);
                assertThat(ps.executeUpdate()).isOne();
            }
            c.commit();
            return before;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void chainBrokenClosesOnlyWithAMatchThatStartedAfterTheFlag() throws Exception {
        String t = tenant();
        Run before = verify(t);
        assertThat(before.result()).isEqualTo("MATCH");
        UUID flag = chainFlag(t, t);
        String path = "/api/v1/flags/" + flag + "/resolve";

        // 근거 없음 · 플래그 이전에 시작한 MATCH
        ApiTestSupport.Response none = post(t, COMPLIANCE, path, resolveBody("VERIFIED_MATCH", null));
        assertThat(none.status()).isEqualTo(422);
        assertThat(rejection(none)).isEqualTo("EVIDENCE_REQUIRED");
        ApiTestSupport.Response early = post(t, COMPLIANCE, path, resolveBody("VERIFIED_MATCH", before.jobId()));
        assertThat(early.status()).isEqualTo(422);
        assertThat(rejection(early)).isEqualTo("CHAIN_EVIDENCE_REJECTED");
        assertThat(early.text()).as("the detail stays in the audit").doesNotContain("STARTED_BEFORE_FLAG");

        // 플래그 뒤의 MISMATCH
        String original = tamper(t, null);
        Run broken = verify(t);
        assertThat(broken.result()).isEqualTo("MISMATCH");
        tamper(t, original);
        ApiTestSupport.Response mismatch = post(t, COMPLIANCE, path, resolveBody("VERIFIED_MATCH", broken.jobId()));
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(rejection(mismatch)).isEqualTo("CHAIN_EVIDENCE_REJECTED");

        // 다른 종류의 작업 · 없는(다른 테넌트의) 작업
        UUID[] otherKind = new UUID[1];
        DB.seed(t, c -> otherKind[0] = SeedData.asyncJob(c, t, "EXPIRE"));
        assertThat(rejection(post(t, COMPLIANCE, path, resolveBody("VERIFIED_MATCH", otherKind[0])))).isEqualTo("CHAIN_EVIDENCE_REJECTED");
        assertThat(rejection(post(t, COMPLIANCE, path, resolveBody("VERIFIED_MATCH", UUID.randomUUID())))).isEqualTo("CHAIN_EVIDENCE_REJECTED");
        // 모르는 해소 코드(그 유형의 룰 목록 밖), 관리자는 해소 칸이 없다
        assertThat(rejection(post(t, COMPLIANCE, path, resolveBody("FALSE_POSITIVE", null)))).isEqualTo("RESOLUTION_CODE_UNKNOWN");
        assertThat(post(t, MANAGER, path, resolveBody("VERIFIED_MATCH", null)).status()).isEqualTo(404);

        assertThat(rejectedAudits(t, flag)).containsExactlyInAnyOrder("EVIDENCE_REQUIRED:", "CHAIN_EVIDENCE_REJECTED:STARTED_BEFORE_FLAG",
                "CHAIN_EVIDENCE_REJECTED:NOT_MATCH", "CHAIN_EVIDENCE_REJECTED:NOT_VERIFY_TENANT", "CHAIN_EVIDENCE_REJECTED:JOB_NOT_FOUND",
                "RESOLUTION_CODE_UNKNOWN:");
        assertThat(openCount(t, flag)).isOne();

        // 플래그 뒤에 시작해 MATCH로 끝난 검증으로 해소
        Run after = verify(t);
        assertThat(after.result()).isEqualTo("MATCH");
        ApiTestSupport.Response ok = post(t, COMPLIANCE, path, resolveBody("VERIFIED_MATCH", after.jobId()));
        assertThat(ok.status()).as(ok.text()).isEqualTo(200);
        assertThat(Canonicalizer.parseStrict(ok.text()).get("status").asString()).isEqualTo("RESOLVED");
        assertThat(DB.<String>asApp(t, c -> SeedData.call(c, """
                SELECT resolution || '|' || resolution_code || '|' || (resolution_evidence ->> 'verifyRunJobId') || '|' || resolved_by
                  FROM compliance_flag WHERE tenant_id = ? AND flag_id = ?""", t, flag)))
                .isEqualTo("COMPLIANCE_RESOLVED|VERIFIED_MATCH|" + after.jobId() + "|" + COMPLIANCE);
        assertThat(DB.<String>asApp(t, c -> SeedData.call(c, """
                SELECT detail ->> 'resolutionCode' || '|' || (detail -> 'evidence' ->> 'verifyRunJobId')
                  FROM audit_log WHERE tenant_id = ? AND action = 'FLAG_RESOLVE' AND target_id = ?""", t, flag.toString())))
                .isEqualTo("VERIFIED_MATCH|" + after.jobId());

        // 재개 없음 — 다시 해소하면 409
        ApiTestSupport.Response again = post(t, COMPLIANCE, path, resolveBody("VERIFIED_MATCH", after.jobId()));
        assertThat(again.status()).isEqualTo(409);
        assertThat(rejection(again)).isEqualTo("ALREADY_RESOLVED");
    }

    static java.util.List<String> rejectedAudits(String t, UUID flag) {
        return DB.asApp(t, c -> {
            java.util.List<String> out = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement("""
                    SELECT detail ->> 'rejected', coalesce(detail ->> 'evidenceProblem', '')
                      FROM audit_log WHERE tenant_id = ? AND action = 'FLAG_COMMAND_REJECTED' AND target_id = ? ORDER BY seq""")) {
                ps.setString(1, t);
                ps.setString(2, flag.toString());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1) + ":" + rs.getString(2));
                    }
                }
            }
            return out;
        });
    }

    static int openCount(String t, UUID flag) {
        return Integer.parseInt(DB.<String>asApp(t, c -> SeedData.call(c,
                "SELECT count(*)::text FROM compliance_flag WHERE tenant_id = ? AND flag_id = ? AND resolved_at IS NULL", t, flag)));
    }
}
