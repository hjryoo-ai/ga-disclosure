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

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5 HTTP(6B 계획 §5·승인 §5): 스냅샷 작업은 스케줄러(`/internal`)·준법(`/api`)이 제출하고, 같은 (달, 룰 버전)의 재제출은 작업을 만들지 않고 409
 * {@code SNAPSHOT_EXISTS}. 조회 {@code GET /api/v1/collection-rates}는 정의 표기(내부 지표 — 규제 정의 없음)·산식·룰 버전을 싣고, 준법은 테넌트 전체,
 * 관리자는 조직 아래, 설계사는 없는 라우트와 같은 404. 응답은 계약 검증을 지난다({@link ApiTestSupport}).
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CollectionRateApiIT {

    static final String SCHEDULER = "scheduler-1";
    static final String COMPLIANCE = "compliance-1";
    static final String MANAGER = "manager-1";
    static final String AGENT = "agent-1";

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
        String t = SeedData.uniqueTenant("RATE");
        DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.roleLink(c, t, SCHEDULER, "SCHEDULER");
            SeedData.roleLink(c, t, COMPLIANCE, "COMPLIANCE");
            SeedData.orgLink(c, t, MANAGER, "MGR-1", "MANAGER", "/HQ/B1");
            SeedData.identityLink(c, t, AGENT, "AGENT-1", "AGENT");
            // 조직 /HQ/B1: 완료(9/24 KST) + 9/30 계약 = 징구, 봉인만 + 9/30 계약 = 미징구
            SeedData.contractLink(c, t, SeedData.disclosure(c, t, "COMPLETED", SeedData.hash('a')), "POL-API-1", "2026-09-30");
            SeedData.contractLink(c, t, SeedData.disclosure(c, t, "SEALED", SeedData.hash('b')), "POL-API-2", "2026-09-30");
        });
        ApiTestSupport.activateRules(distribution, activation, t, null, body -> {
        });
        return t;
    }

    ApiTestSupport.Response post(String tenant, String subject, String path, String json) {
        return ApiTestSupport.post(port, path, TestJwts.token(tenant, subject), json, Map.of("Idempotency-Key", "it-" + UUID.randomUUID()));
    }

    ApiTestSupport.Response get(String tenant, String subject, String path) {
        return ApiTestSupport.get(port, path, TestJwts.token(tenant, subject));
    }

    String finished(String tenant, String job) throws InterruptedException {
        String status = "";
        for (long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos(); System.nanoTime() < deadline; Thread.sleep(50)) {
            status = Canonicalizer.parseStrict(get(tenant, COMPLIANCE, "/api/v1/jobs/" + job).text()).get("status").asString();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                break;
            }
        }
        return status;
    }

    long jobs(String tenant) {
        return Long.parseLong(DB.asApp(tenant, c -> SeedData.call(c,
                "SELECT count(*)::text FROM async_job WHERE tenant_id = ? AND kind = 'COLLECTION_RATE_SNAPSHOT'", tenant)));
    }

    @Test
    void theSchedulerSnapshotsOnceAndReadsAreScopedAndLabelled() throws Exception {
        String t = tenant();
        String path = "/internal/v1/jobs/COLLECTION_RATE_SNAPSHOT";
        // 형식: 끝나지 않은 달·모르는 키·형식 틀림은 400(작업 없음)
        assertThat(post(t, SCHEDULER, path, "{\"periodMonth\":\"2999-01\"}").status()).isEqualTo(400);
        assertThat(post(t, SCHEDULER, path, "{\"limit\":5}").status()).isEqualTo(400);
        assertThat(post(t, SCHEDULER, path, "{\"periodMonth\":\"2026-9\"}").status()).isEqualTo(400);
        assertThat(jobs(t)).isZero();

        ApiTestSupport.Response queued = post(t, SCHEDULER, path, "{\"periodMonth\":\"2026-09\"}");
        assertThat(queued.status()).as(queued.text()).isEqualTo(202);
        String job = Canonicalizer.parseStrict(queued.text()).get("jobId").asString();
        assertThat(finished(t, job)).isEqualTo("SUCCEEDED");
        JsonNode report = Canonicalizer.parseStrict(get(t, COMPLIANCE, "/api/v1/jobs/" + job + "/report").text());
        assertThat(report.get("kind").asString()).isEqualTo("COLLECTION_RATE_SNAPSHOT");
        assertThat(report.get("definition").asString()).isEqualTo("INTERNAL_METRIC_NO_REGULATORY_DEFINITION");
        assertThat(report.get("formula").asString()).isEqualTo("LINKED_COMPLETED_BY_CONTRACT_DATE");
        assertThat(report.get("groups").get(0).get("rateBp").asInt()).isEqualTo(5000);
        assertThat(report.toString()).doesNotContain("POL-API");

        // 같은 (달, 룰 버전) 재제출 — 스케줄러든 준법이든 작업 없이 409
        long before = jobs(t);
        for (String[] who : new String[][] {{SCHEDULER, path}, {COMPLIANCE, "/api/v1/jobs/COLLECTION_RATE_SNAPSHOT"}}) {
            ApiTestSupport.Response again = post(t, who[0], who[1], "{\"periodMonth\":\"2026-09\"}");
            assertThat(again.status()).as(who[0]).isEqualTo(409);
            assertThat(again.text()).contains("\"code\":\"SNAPSHOT_EXISTS\"");
        }
        assertThat(jobs(t)).isEqualTo(before);

        // 조회: 준법은 테넌트 행 포함, 관리자는 조직 아래, 설계사는 없는 라우트와 같은 404
        String q = "/api/v1/collection-rates?from=2026-08&to=2026-09";
        JsonNode all = Canonicalizer.parseStrict(get(t, COMPLIANCE, q).text());
        assertThat(all.get("definition").asString()).isEqualTo("INTERNAL_METRIC_NO_REGULATORY_DEFINITION");
        assertThat(all.get("definitionText").asString()).isEqualTo("내부 지표 — 규제 정의 없음");
        assertThat(all.get("items")).hasSize(2);
        assertThat(all.get("items").get(0).get("orgPath").asString()).isEqualTo("/");
        assertThat(all.get("items").get(0).get("ruleVersionId").asString()).isEqualTo("DISC-2026-07");
        assertThat(all.get("items").get(1).get("orgPath").asString()).isEqualTo("/HQ/B1");
        assertThat(all.get("items").get(1).get("rateBp").asInt()).isEqualTo(5000);
        JsonNode mine = Canonicalizer.parseStrict(get(t, MANAGER, q).text());
        assertThat(mine.get("items")).singleElement().satisfies(i -> assertThat(i.get("orgPath").asString()).isEqualTo("/HQ/B1"));
        ApiTestSupport.Response noRoute = get(t, AGENT, "/api/v1/no-such-route");
        ApiTestSupport.Response agent = get(t, AGENT, q);
        assertThat(agent.status()).isEqualTo(404);
        assertThat(agent.fingerprint()).isEqualTo(noRoute.fingerprint());
        assertThat(get(t, COMPLIANCE, "/api/v1/collection-rates?from=2026-13&to=2026-09").status()).isEqualTo(400);
        assertThat(get(t, COMPLIANCE, "/api/v1/collection-rates?from=2026-09").status()).isEqualTo(400);
        assertThat(Canonicalizer.parseStrict(get(t, COMPLIANCE, q + "&ruleVersionId=DISC-2027-01").text()).get("items")).isEmpty();
    }
}
