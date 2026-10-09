package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.audit.verify.VerifySchemas;
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

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G8 HTTP(6B 계획 §8): 보존 재계산 작업은 준법만 {@code /api}로 제출한다(스케줄러·관리자는 없는 라우트와 같은 404). 기본 dry-run은 보고서만,
 * {@code apply:true}는 더 긴 후보만 쓴다. 쓸 수 없는 룰 버전은 작업을 만들지 않고 422 {@code RULE_VERSION_NOT_USABLE}, 형식 오류는 400.
 * 보고서는 계약 스키마를 지난다.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetentionRecomputeApiIT {

    static final String SCHEDULER = "scheduler-1";
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

    ApiTestSupport.Response post(String tenant, String subject, String path, String json) {
        return ApiTestSupport.post(port, path, TestJwts.token(tenant, subject), json, Map.of("Idempotency-Key", "it-" + UUID.randomUUID()));
    }

    ApiTestSupport.Response get(String tenant, String subject, String path) {
        return ApiTestSupport.get(port, path, TestJwts.token(tenant, subject));
    }

    JsonNode report(String tenant, ApiTestSupport.Response queued) throws InterruptedException {
        assertThat(queued.status()).as(queued.text()).isEqualTo(202);
        String job = Canonicalizer.parseStrict(queued.text()).get("jobId").asString();
        String status = "";
        for (long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos(); System.nanoTime() < deadline; Thread.sleep(50)) {
            status = Canonicalizer.parseStrict(get(tenant, COMPLIANCE, "/api/v1/jobs/" + job).text()).get("status").asString();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                break;
            }
        }
        assertThat(status).isEqualTo("SUCCEEDED");
        JsonNode report = Canonicalizer.parseStrict(get(tenant, COMPLIANCE, "/api/v1/jobs/" + job + "/report").text());
        assertThat(VerifySchemas.retentionRecomputeReport(report)).isEmpty();
        return report;
    }

    long jobs(String tenant) {
        return Long.parseLong(DB.asApp(tenant, c -> SeedData.call(c,
                "SELECT count(*)::text FROM async_job WHERE tenant_id = ? AND kind = 'RETENTION_RECOMPUTE'", tenant)));
    }

    String retention(String tenant, UUID id) {
        return DB.asApp(tenant, c -> SeedData.call(c, "SELECT retention_until::text FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", tenant,
                id));
    }

    @Test
    void complianceDryRunsThenAppliesAndUnusableVersionsNeverBecomeJobs() throws Exception {
        String t = SeedData.uniqueTenant("RECOMP");
        UUID[] ids = new UUID[2];
        DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.roleLink(c, t, SCHEDULER, "SCHEDULER");
            SeedData.roleLink(c, t, COMPLIANCE, "COMPLIANCE");
            SeedData.orgLink(c, t, MANAGER, "MGR-1", "MANAGER", "/HQ/B1");
            // 봉인 9/23·완료 9/24(KST), 시드 보존기한은 봉인 기준 2031-09-23 — 완료 기준점이 더 늦다
            ids[0] = SeedData.disclosure(c, t, "COMPLETED", SeedData.hash('a'));
            ids[1] = SeedData.disclosure(c, t, "SEALED", SeedData.hash('b'));
        });
        ApiTestSupport.activateRules(distribution, activation, t, null, body -> {
        });
        String path = "/api/v1/jobs/RETENTION_RECOMPUTE";

        // 형식 오류는 400, 쓸 수 없는 버전은 422 — 어느 쪽도 작업이 아니다
        assertThat(post(t, COMPLIANCE, path, "{}").status()).isEqualTo(400);
        assertThat(post(t, COMPLIANCE, path, "{\"ruleVersionId\":\"disc-lower\"}").status()).isEqualTo(400);
        assertThat(post(t, COMPLIANCE, path, "{\"ruleVersionId\":\"DISC-2026-07\",\"limit\":5}").status()).isEqualTo(400);
        ApiTestSupport.Response unusable = post(t, COMPLIANCE, path, "{\"ruleVersionId\":\"DISC-NO-SUCH\"}");
        assertThat(unusable.status()).isEqualTo(422);
        assertThat(unusable.text()).contains("\"code\":\"RULE_VERSION_NOT_USABLE\"");
        assertThat(jobs(t)).isZero();

        // 스케줄러(/internal)·관리자(/api)는 칸이 없다 — 없는 라우트와 같은 404
        ApiTestSupport.Response noRoute = get(t, MANAGER, "/api/v1/no-such-route");
        assertThat(post(t, MANAGER, path, "{\"ruleVersionId\":\"DISC-2026-07\"}").fingerprint()).isEqualTo(noRoute.fingerprint());
        ApiTestSupport.Response internalNoRoute = get(t, SCHEDULER, "/internal/v1/no-such-route");
        assertThat(post(t, SCHEDULER, "/internal/v1/jobs/RETENTION_RECOMPUTE", "{\"ruleVersionId\":\"DISC-2026-07\"}").fingerprint())
                .isEqualTo(internalNoRoute.fingerprint());
        assertThat(jobs(t)).isZero();

        // dry-run(기본): 완료 건만 연장 후보, 쓰기 없음
        JsonNode dry = report(t, post(t, COMPLIANCE, path, "{\"ruleVersionId\":\"DISC-2026-07\"}"));
        assertThat(dry.get("apply").asBoolean()).isFalse();
        assertThat(dry.get("extended").asInt()).isOne();
        assertThat(dry.get("unchanged").asInt()).isOne();
        assertThat(retention(t, ids[0])).isEqualTo("2031-09-23");

        // 적용: 더 긴 후보만 쓴다, 재실행은 전부 UNCHANGED
        JsonNode applied = report(t, post(t, COMPLIANCE, path, "{\"ruleVersionId\":\"DISC-2026-07\",\"apply\":true}"));
        assertThat(applied.get("extended").asInt()).isOne();
        assertThat(retention(t, ids[0])).isEqualTo("2031-09-24");
        assertThat(retention(t, ids[1])).isEqualTo("2031-09-23");
        JsonNode again = report(t, post(t, COMPLIANCE, path, "{\"ruleVersionId\":\"DISC-2026-07\",\"apply\":true}"));
        assertThat(again.get("extended").asInt()).isZero();
        assertThat(again.get("unchanged").asInt()).isEqualTo(2);
    }
}
