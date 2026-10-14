package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.compliance.rules.RuleActivationJob;
import com.ga.disclosure.compliance.rules.RuleDistributionService;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6B 이월 ①(8 계획 승인): 오늘 시행 중인 룰이 없는 테넌트의 쓰기 요청은 <b>계약의 쓰기 연산 전수</b>({@code /api}·{@code /internal})에서 503
 * {@code TENANT_RULES_NOT_ACTIVE}이고 500은 0이다. 감사 행·멱등 행을 남기지 않아 온보딩 뒤 같은 키는 처음 요청이다. 공개 경로는 없는 테넌트와 같은 거부 그대로.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantRulesNotActiveIT {

    private static final Set<String> WRITES = Set.of("post", "put", "patch", "delete");

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

    record Operation(String method, String path) {
    }

    static List<Operation> writes(String file) throws Exception {
        JsonNode doc = YAMLMapper.builder().build().readTree(Files.readString(ApiTestSupport.ROOT.resolve("contracts/api/v1").resolve(file)));
        List<Operation> out = new ArrayList<>();
        for (var path : doc.get("paths").properties()) {
            for (var op : path.getValue().properties()) {
                if (WRITES.contains(op.getKey())) {
                    out.add(new Operation(op.getKey().toUpperCase(java.util.Locale.ROOT),
                            path.getKey().replace("{kind}", "NOTIFY").replaceAll("\\{[A-Za-z]+}", UUID.randomUUID().toString())));
                }
            }
        }
        return out;
    }

    @Test
    void everyWriteOfATenantWithoutRulesIsThe503AndLeavesNoRows() throws Exception {
        String t = SeedData.uniqueTenant("NORULE");
        ApiTestSupport.DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.identityLink(c, t, "agent-1", "AGENT-1", "AGENT");
            SeedData.roleLink(c, t, "scheduler-1", "SCHEDULER");
        });
        List<Operation> api = writes("disclosure-api.openapi.yaml");
        List<Operation> internal = writes("disclosure-internal.openapi.yaml");
        assertThat(api).hasSizeGreaterThan(15);
        assertThat(internal).hasSizeGreaterThanOrEqualTo(4);

        List<String> notThe503 = new ArrayList<>();
        String key = "norule-" + UUID.randomUUID();
        for (Operation op : api) {
            check(op, TestJwts.token(t, "agent-1"), key, notThe503);
        }
        for (Operation op : internal) {
            check(op, TestJwts.token(t, "scheduler-1"), key, notThe503);
        }
        assertThat(notThe503).isEmpty();
        long rows = Long.parseLong(ApiTestSupport.DB.asApp(t, c -> SeedData.call(c,
                "SELECT ((SELECT count(*) FROM audit_log WHERE tenant_id = ?) + (SELECT count(*) FROM idempotency_key WHERE tenant_id = ?))::text", t, t)));
        assertThat(rows).as("감사·멱등 행 0").isZero();

        // 온보딩(룰 배포·활성화) 뒤 같은 키의 같은 요청은 처음 요청이다 — 503이 키를 묶지 않았다
        ApiTestSupport.activateRules(distribution, activation, t, null, body -> {
        });
        ApiTestSupport.Response after = ApiTestSupport.post(port, "/internal/v1/jobs/IDEMPOTENCY_PURGE", TestJwts.token(t, "scheduler-1"), "{}",
                Map.of("Idempotency-Key", key));
        assertThat(after.status()).as(after.text()).isEqualTo(202);
        assertThat(after.headers()).doesNotContainKey("idempotency-replayed");
    }

    /** 공개 경로는 이 503을 내지 않는다 — 룰 없는 테넌트의 토큰도 없는 테넌트와 같은 거부 바이트. */
    @Test
    void publicPathsKeepTheUniformRejection() {
        String t = SeedData.uniqueTenant("NORULEP");
        ApiTestSupport.DB.seed(t, c -> SeedData.tenant(c, t));
        String fake = t + "~" + "A".repeat(43);
        ApiTestSupport.Response known = ApiTestSupport.post(port, "/public/v1/sign/status", null, "{}", Map.of("X-Sign-Token", fake));
        ApiTestSupport.Response unknown = ApiTestSupport.post(port, "/public/v1/sign/status", null, "{}",
                Map.of("X-Sign-Token", "NOPE" + SeedData.uniqueTenant("Z") + "~" + "A".repeat(43)));
        assertThat(known.status()).isEqualTo(404);
        assertThat(known.fingerprint()).isEqualTo(unknown.fingerprint());
    }

    private void check(Operation op, String token, String key, List<String> notThe503) {
        ApiTestSupport.Response r = ApiTestSupport.send(port, op.method(), op.path(), token, op.method().equals("DELETE") ? null : "{}",
                Map.of("Idempotency-Key", key));
        if (r.status() != 503 || !r.text().contains("\"code\":\"TENANT_RULES_NOT_ACTIVE\"")) {
            notThe503.add(op.method() + " " + op.path().replaceAll("[0-9a-f-]{36}", "{id}") + " → " + r.status());
        }
    }
}
