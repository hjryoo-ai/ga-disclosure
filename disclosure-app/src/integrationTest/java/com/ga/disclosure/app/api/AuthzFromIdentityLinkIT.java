package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1(6A 계획 §11): 역할·조직·{@code agent_id}는 토큰 클레임이 아니라 {@code identity_link}에서 온다. {@code roles}·{@code scope}·{@code org_path}·
 * {@code agent_id} 클레임을 넣은 설계사 토큰은 설계사로만 동작하고(타인 확인서 404, 준법 행위 404, 자기 확인서 200), {@code identity_link}의 역할을 바꾸면
 * 다음 요청에 바로 반영된다(캐시 없음) — 그때도 클레임은 아무것도 열지 못한다.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthzFromIdentityLinkIT {

    static final String T = SeedData.uniqueTenant("CLM");
    static final Map<String, Object> ELEVATING = Map.of("roles", List.of("COMPLIANCE", "MANAGER"), "scope", "admin disclosures:*", "org_path", "/",
            "agent_id", "DEMO-AGENT-X");
    static String customerRef;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void prepare() {
        customerRef = FlowSupport.prepare(T);
        DB.seed(T, c -> SeedData.roleLink(c, T, "compliance-2", "COMPLIANCE"));
    }

    @Value("${local.server.port}")
    int port;

    static String tokenWithClaims(String subject) {
        Map<String, Object> claims = new java.util.HashMap<>(ELEVATING);
        claims.put("tenant_id", T);
        return TestJwts.sign(false, claims, subject, TestJwts.ISSUER, TestJwts.AUDIENCE, Duration.ofMinutes(10));
    }

    String draftBy(String subject) {
        ApiTestSupport.Response created = FlowSupport.post(port, T, subject, "/api/v1/disclosures", "{\"customerRef\":\"" + customerRef
                + "\",\"groupCode\":\"PG-HEALTH-SIMPLE-NR\",\"consultDate\":\"2026-09-25\",\"templateType\":\"STANDARD\"}");
        assertThat(created.status()).as(created.text()).isEqualTo(201);
        return Canonicalizer.parseStrict(created.text()).get("disclosureId").asString();
    }

    static void setRoles(String subject, String role) throws Exception {
        try (var c = DB.superuserDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            assertThat(s.executeUpdate("UPDATE identity_link SET roles = ARRAY['" + role + "'] WHERE tenant_id = '" + T + "' AND subject = '" + subject
                    + "'")).isEqualTo(1);
        }
    }

    @Test
    void roleScopeOrgAndAgentClaimsAreIgnored() {
        String own = draftBy("agent-1");
        String others = draftBy("agent-x");
        String elevated = tokenWithClaims("agent-1");

        assertThat(ApiTestSupport.get(port, "/api/v1/disclosures/" + own, elevated).status()).as("still the agent it is").isEqualTo(200);
        ApiTestSupport.Response foreign = ApiTestSupport.get(port, "/api/v1/disclosures/" + others, elevated);
        assertThat(foreign.status()).as("agent_id/org_path claims do not widen the scope").isEqualTo(404);
        assertThat(foreign.fingerprint()).isEqualTo(ApiTestSupport.get(port, "/api/v1/disclosures/" + others, TestJwts.token(T, "agent-1")).fingerprint());
        assertThat(ApiTestSupport.get(port, "/api/v1/legal-holds", elevated).status()).as("roles claim grants no compliance action").isEqualTo(404);
        assertThat(ApiTestSupport.get(port, "/api/v1/jobs", elevated).status()).isEqualTo(404);
        assertThat(ApiTestSupport.get(port, "/internal/v1/events", elevated).status()).isEqualTo(404);
    }

    @Test
    void anIdentityLinkChangeTakesEffectOnTheNextRequest() throws Exception {
        String plain = TestJwts.token(T, "compliance-2");
        String elevated = tokenWithClaims("compliance-2");
        assertThat(ApiTestSupport.get(port, "/api/v1/legal-holds", plain).status()).isEqualTo(200);
        try {
            setRoles("compliance-2", "FEED_CONSUMER");
            assertThat(ApiTestSupport.get(port, "/api/v1/legal-holds", plain).status()).as("the link changed — no cached role").isEqualTo(404);
            assertThat(ApiTestSupport.get(port, "/api/v1/legal-holds", elevated).status()).as("a COMPLIANCE claim does not bring it back")
                    .isEqualTo(404);
            assertThat(ApiTestSupport.get(port, "/internal/v1/events", plain).status()).as("now a feed consumer").isEqualTo(200);
        } finally {
            setRoles("compliance-2", "COMPLIANCE");
        }
        assertThat(ApiTestSupport.get(port, "/api/v1/legal-holds", plain).status()).isEqualTo(200);
    }
}
