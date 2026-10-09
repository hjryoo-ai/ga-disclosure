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
 * G4 HTTP 입구(6B 계획 §4): {@code POST /internal/v1/contract-links}는 계약 피드 서비스 주체만 — 다른 주체는 없는 라우트와 같은 404(본문이 틀려도 404, 인가가
 * 해석보다 먼저). 스키마 위반은 400 {@code MALFORMED_REQUEST}(위치·값 없음). 통과하면 202 + 작업이고, 작업 행의 매개변수·보고서에는 번호가 없다. 배치는 일반
 * 작업 경로로 제출할 수 없다(404).
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractLinkApiIT {

    static final String FEED = "contract-feed-1";
    static final String SCHEDULER = "scheduler-1";
    static final String COMPLIANCE = "compliance-1";
    static final String SECRET = "POL-SECRET-" + UUID.randomUUID().toString().substring(0, 8);

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
        String t = SeedData.uniqueTenant("CLINK");
        DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.feedLink(c, t, FEED, "INS_FEED_A");
            SeedData.roleLink(c, t, SCHEDULER, "SCHEDULER");
            SeedData.roleLink(c, t, COMPLIANCE, "COMPLIANCE");
        });
        ApiTestSupport.activateRules(distribution, activation, t, null, body -> {
        });
        return t;
    }

    ApiTestSupport.Response post(String tenant, String subject, String path, String json) {
        return ApiTestSupport.post(port, path, TestJwts.token(tenant, subject), json, Map.of("Idempotency-Key", "it-" + UUID.randomUUID()));
    }

    static String batch(String batchId, String policy) {
        return "{\"schemaVersion\":1,\"source\":\"INS_FEED_A\",\"batchId\":\"" + batchId + "\",\"items\":[{\"policyNo\":\"" + policy
                + "\",\"contractDate\":\"2026-09-30\",\"insurerCode\":\"INS-A\"}]}";
    }

    @Test
    void onlyTheContractFeedSubmitsAndNoNumberReachesTheJobRowOrReport() throws Exception {
        String t = tenant();
        ApiTestSupport.Response noRoute = post(t, SCHEDULER, "/internal/v1/no-such-route", "{}");
        // 다른 주체: 맞는 본문이든 틀린 본문이든 같은 404
        for (String body : new String[]{batch("B0", SECRET), "{\"broken\":true}"}) {
            ApiTestSupport.Response denied = post(t, SCHEDULER, "/internal/v1/contract-links", body);
            assertThat(denied.status()).isEqualTo(404);
            assertThat(denied.fingerprint()).isEqualTo(noRoute.fingerprint());
        }
        // 일반 작업 경로로는 제출할 수 없다
        assertThat(post(t, SCHEDULER, "/internal/v1/jobs/CONTRACT_LINK_IMPORT", "{}").status()).isEqualTo(404);
        // 피드의 스키마 위반 = 400, 값·위치 없음
        ApiTestSupport.Response malformed = post(t, FEED, "/internal/v1/contract-links",
                batch("B1", SECRET).replace("\"INS-A\"", "\"INS_A\""));
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(Canonicalizer.parseStrict(malformed.text()).get("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(malformed.text()).doesNotContain(SECRET).doesNotContain("insurerCode");

        ApiTestSupport.Response accepted = post(t, FEED, "/internal/v1/contract-links", batch("B2", SECRET));
        assertThat(accepted.status()).as(accepted.text()).isEqualTo(202);
        assertThat(accepted.text()).doesNotContain(SECRET);
        String job = Canonicalizer.parseStrict(accepted.text()).get("jobId").asString();
        String status = "";
        for (long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos(); System.nanoTime() < deadline; Thread.sleep(50)) {
            status = Canonicalizer.parseStrict(ApiTestSupport.get(port, "/api/v1/jobs/" + job, TestJwts.token(t, COMPLIANCE)).text()).get("status").asString();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                break;
            }
        }
        assertThat(status).isEqualTo("SUCCEEDED");
        JsonNode report = Canonicalizer.parseStrict(ApiTestSupport.get(port, "/api/v1/jobs/" + job + "/report", TestJwts.token(t, COMPLIANCE)).text());
        assertThat(report.at("/counts/UNMATCHED").asInt()).isOne();
        assertThat(report.toString()).doesNotContain(SECRET);
        assertThat(DB.<String>asApp(t, c -> SeedData.call(c, "SELECT params::text FROM async_job WHERE tenant_id = ? AND job_id = CAST(? AS uuid)", t, job)))
                .contains("\"batchId\": \"B2\"").contains("\"items\": 1").doesNotContain(SECRET);
        assertThat(DB.<String>asApp(t, c -> SeedData.call(c, "SELECT string_agg(detail::text, '') FROM audit_log WHERE tenant_id = ?", t)))
                .doesNotContain(SECRET);
        // 보고 행에는 번호가 남는다(룰 기간 뒤 정리 — pii-columns PURGED)
        assertThat(DB.<String>asApp(t, c -> SeedData.call(c, "SELECT reason FROM contract_link_unmatched WHERE tenant_id = ? AND policy_no = ?", t, SECRET)))
                .isEqualTo("UNMATCHED");
    }

    String waitFor(String t, String job) throws InterruptedException {
        String status = "";
        for (long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos(); System.nanoTime() < deadline; Thread.sleep(50)) {
            status = Canonicalizer.parseStrict(ApiTestSupport.get(port, "/api/v1/jobs/" + job, TestJwts.token(t, COMPLIANCE)).text()).get("status").asString();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                break;
            }
        }
        return status;
    }

    /**
     * 6B 중간 회신 ①②: 피드 주체는 자기 출처로만 보낸다 — 목록 밖 출처는 본문이 맞아도 없는 라우트와 같은 404(작업 없음). 같은 출처·배치 ID에 다른 내용은
     * 작업을 만들기 전에 422 {@code BATCH_REF_REUSED}(번호 없음), 같은 내용 재전송은 202(재생).
     */
    @Test
    void aFeedSendsOnlyItsOwnSourcesAndABatchReferenceCarriesOneContent() throws Exception {
        String t = tenant();
        ApiTestSupport.Response noRoute = post(t, FEED, "/internal/v1/no-such-route", "{}");
        ApiTestSupport.Response otherSource = post(t, FEED, "/internal/v1/contract-links", batch("S1", SECRET).replace("INS_FEED_A", "INS_FEED_B"));
        assertThat(otherSource.status()).isEqualTo(404);
        assertThat(otherSource.fingerprint()).isEqualTo(noRoute.fingerprint());
        assertThat(DB.<String>asApp(t, c -> SeedData.call(c, "SELECT count(*)::text FROM async_job WHERE tenant_id = ?", t))).isEqualTo("0");

        ApiTestSupport.Response first = post(t, FEED, "/internal/v1/contract-links", batch("S2", SECRET));
        assertThat(first.status()).as(first.text()).isEqualTo(202);
        assertThat(waitFor(t, Canonicalizer.parseStrict(first.text()).get("jobId").asString())).isEqualTo("SUCCEEDED");

        ApiTestSupport.Response reused = post(t, FEED, "/internal/v1/contract-links", batch("S2", SECRET + "-CHANGED"));
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.text()).contains("\"code\":\"BATCH_REF_REUSED\"").doesNotContain(SECRET);
        assertThat(DB.<String>asApp(t, c -> SeedData.call(c, "SELECT count(*)::text FROM async_job WHERE tenant_id = ?", t))).isEqualTo("1");

        ApiTestSupport.Response replay = post(t, FEED, "/internal/v1/contract-links", batch("S2", SECRET));
        assertThat(replay.status()).as(replay.text()).isEqualTo(202);
        String job = Canonicalizer.parseStrict(replay.text()).get("jobId").asString();
        assertThat(waitFor(t, job)).isEqualTo("SUCCEEDED");
        JsonNode report = Canonicalizer.parseStrict(ApiTestSupport.get(port, "/api/v1/jobs/" + job + "/report", TestJwts.token(t, COMPLIANCE)).text());
        assertThat(report.get("replayed").asBoolean()).isTrue();
    }
}
