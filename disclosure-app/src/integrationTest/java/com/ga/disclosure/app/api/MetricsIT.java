package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.app.metrics.MetricsConfiguration;
import com.ga.disclosure.compliance.rules.RuleActivationJob;
import com.ga.disclosure.compliance.rules.RuleDistributionService;
import com.ga.disclosure.infra.testing.SeedData;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G8 운영 미터(8 계획 ③ 관측): 작업 성공·실패·소요, 공개 경로 거부, SLA 초과가 관리 포트의 {@code /actuator/prometheus}에 나오고, {@code ga_} 계열의 라벨 키는
 * 전부 닫힌 목록 안이며 값에 확인서 ID·주체·고객 참조가 없다. 목록 밖 키를 단 미터는 등록부터 거부된다.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SERIES = Pattern.compile("^(ga_[a-z_]+)\\{([^}]*)}", Pattern.MULTILINE);
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_]+)=\"");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int management;

    @Autowired
    RuleDistributionService distribution;

    @Autowired
    RuleActivationJob activation;

    @Autowired
    MeterRegistry registry;

    @Test
    void jobsRejectionsAndBreachesAreMeteredWithClosedLabelKeysOnly() throws Exception {
        String t = SeedData.uniqueTenant("METER");
        ApiTestSupport.DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.roleLink(c, t, "scheduler-1", "SCHEDULER");
            SeedData.roleLink(c, t, "compliance-1", "COMPLIANCE");
        });
        ApiTestSupport.activateRules(distribution, activation, t, null, body -> {
        });
        String purge = submit(t, "IDEMPOTENCY_PURGE");
        String sweep = submit(t, "FLAG_SLA_SWEEP");
        assertThat(ApiTestSupport.post(port, "/public/v1/sign/status", null, "{}", Map.of("X-Sign-Token", "not-a-token")).status()).isEqualTo(404);

        String scrape = ApiTestSupport.get(management, "/actuator/prometheus", null).text();
        assertThat(scrape).contains("ga_jobs_runs_total{kind=\"IDEMPOTENCY_PURGE\",outcome=\"SUCCEEDED\",tenant=\"" + t + "\"}")
                .contains("ga_jobs_runs_total{kind=\"FLAG_SLA_SWEEP\",outcome=\"SUCCEEDED\",tenant=\"" + t + "\"}")
                .contains("ga_jobs_duration_seconds_count{kind=\"FLAG_SLA_SWEEP\",tenant=\"" + t + "\"}")
                .contains("ga_flags_sla_breached_total{tenant=\"" + t + "\"}")
                .contains("ga_public_rejections_total{reason=\"MALFORMED\"}")
                .contains("ga_anchor_unstamped_days ");

        Set<String> keys = new TreeSet<>();
        Matcher m = SERIES.matcher(scrape);
        int series = 0;
        while (m.find()) {
            series++;
            Matcher l = LABEL.matcher(m.group(2));
            while (l.find()) {
                keys.add(l.group(1));
            }
            assertThat(m.group(2)).as("라벨 값에 작업 ID 없음").doesNotContain(purge, sweep);
        }
        assertThat(series).isGreaterThanOrEqualTo(5);
        assertThat(keys).isSubsetOf(MetricsConfiguration.LABEL_KEYS);
        // 관리 포트만 — 앱 포트의 같은 경로는 없는 경로와 같은 404
        assertThat(ApiTestSupport.get(port, "/actuator/prometheus", null).status()).isEqualTo(404);
    }

    @Test
    void aMeterWithAnyOtherLabelKeyIsRefusedAtRegistration() {
        for (String key : List.of("disclosureNo", "disclosureId", "subject", "customerRef", "jobId")) {
            assertThatThrownBy(() -> registry.counter("ga.jobs.runs", "kind", "EXPIRE", key, "x"))
                    .as(key).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
        }
        // 다른 접두의 미터(프레임워크)는 이 목록의 대상이 아니다
        registry.counter("jvm.test.unrelated", "anything", "ok");
        assertThat(Arrays.asList("kind", "outcome", "reason", "tenant")).containsExactlyInAnyOrderElementsOf(MetricsConfiguration.LABEL_KEYS);
    }

    private String submit(String t, String kind) throws Exception {
        ApiTestSupport.Response queued = ApiTestSupport.post(port, "/internal/v1/jobs/" + kind, TestJwts.token(t, "scheduler-1"), "{}",
                Map.of("Idempotency-Key", "meter-" + UUID.randomUUID()));
        assertThat(queued.status()).as(queued.text()).isEqualTo(202);
        String job = JSON.readTree(queued.text()).get("jobId").asString();
        String status = "";
        for (long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos(); System.nanoTime() < deadline; Thread.sleep(50)) {
            JsonNode body = JSON.readTree(ApiTestSupport.get(port, "/api/v1/jobs/" + job, TestJwts.token(t, "compliance-1")).text());
            status = body.get("status").asString();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                break;
            }
        }
        assertThat(status).isEqualTo("SUCCEEDED");
        return job;
    }
}
