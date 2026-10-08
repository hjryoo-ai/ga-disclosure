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

import java.util.Map;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 법적 보류 경로(6A 계획 §4.1): 준법이 설정 201·해제 200(영수증), 목록에 사유 텍스트 없음(개인정보 컬럼), 4-eyes·룰 밖 사유는 422 {@code REJECTED}, 설계사는
 * 404(칸 없음).
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LegalHoldApiIT {

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
        return ApiTestSupport.post(port, path, TestJwts.token(tenant, subject), json, Map.of("Idempotency-Key", "hold-" + UUID.randomUUID()));
    }

    @Test
    void complianceHoldsAndReleasesWithFourEyes() {
        String t = SeedData.uniqueTenant("HOLD");
        UUID[] draft = new UUID[1];
        DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.roleLink(c, t, "compliance-1", "COMPLIANCE");
            SeedData.roleLink(c, t, "compliance-2", "COMPLIANCE");
            SeedData.orgLink(c, t, "agent-a", "A-1", "AGENT", "/HQ/B1");
            draft[0] = SeedData.draftBy(c, t, "A-1", "/HQ/B1", "2026-09-01");
        });
        ApiTestSupport.activateRules(distribution, activation, t, null, body -> {
        });
        String sentinel = "보류-텍스트-센티널";
        ApiTestSupport.Response placed = post(t, "compliance-1", "/api/v1/legal-holds",
                "{\"disclosureId\":\"" + draft[0] + "\",\"reasonCode\":\"OTHER\",\"reasonText\":\"" + sentinel + "\"}");
        assertThat(placed.status()).as(placed.text()).isEqualTo(201);
        JsonNode receipt = Canonicalizer.parseStrict(placed.text());
        String holdId = receipt.get("holdId").asString();
        assertThat(placed.text()).doesNotContain(sentinel);

        ApiTestSupport.Response again = post(t, "compliance-1", "/api/v1/legal-holds", "{\"disclosureId\":\"" + draft[0] + "\",\"reasonCode\":\"LITIGATION\"}");
        assertThat(again.status()).isEqualTo(422);
        assertThat(again.text()).contains("\"code\":\"ALREADY_HELD\"");
        assertThat(post(t, "compliance-1", "/api/v1/legal-holds", "{\"disclosureId\":\"" + draft[0] + "\",\"reasonCode\":\"NOPE\"}").text())
                .contains("UNKNOWN_REASON");
        assertThat(post(t, "compliance-1", "/api/v1/legal-holds", "{\"reasonCode\":\"LITIGATION\"}").text()).contains("\"field\":\"target\"");
        assertThat(post(t, "agent-a", "/api/v1/legal-holds", "{\"disclosureId\":\"" + draft[0] + "\",\"reasonCode\":\"LITIGATION\"}").status())
                .isEqualTo(404);

        ApiTestSupport.Response list = ApiTestSupport.get(port, "/api/v1/legal-holds", TestJwts.token(t, "compliance-2"));
        assertThat(list.status()).isEqualTo(200);
        JsonNode item = Canonicalizer.parseStrict(list.text()).get("items").get(0);
        assertThat(item.get("holdId").asString()).isEqualTo(holdId);
        assertThat(item.get("reasonCode").asString()).isEqualTo("OTHER");
        assertThat(list.text()).doesNotContain(sentinel);

        ApiTestSupport.Response self = post(t, "compliance-1", "/api/v1/legal-holds/" + holdId + "/release", "{\"reasonCode\":\"CASE_CLOSED\"}");
        assertThat(self.status()).isEqualTo(422);
        assertThat(self.text()).contains("FOUR_EYES_REQUIRED");
        ApiTestSupport.Response released = post(t, "compliance-2", "/api/v1/legal-holds/" + holdId + "/release", "{\"reasonCode\":\"CASE_CLOSED\"}");
        assertThat(released.status()).as(released.text()).isEqualTo(200);
        assertThat(Canonicalizer.parseStrict(ApiTestSupport.get(port, "/api/v1/legal-holds", TestJwts.token(t, "compliance-2")).text())
                .get("items").get(0).get("releaseReasonCode").asString()).isEqualTo("CASE_CLOSED");
        assertThat(ApiTestSupport.get(port, "/api/v1/legal-holds", TestJwts.token(t, "agent-a")).status()).isEqualTo(404);
    }
}
