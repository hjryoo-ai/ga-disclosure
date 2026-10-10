package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static com.ga.disclosure.app.api.ApiTestSupport.sendExact;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부 경로 포트 분리(8 계획 승인 Q5, 앱 쪽 한 겹): {@code /internal/**}은 내부 포트에서만 응답하고, 앱 포트로 온 {@code /internal/**}·내부 포트로 온
 * {@code /api/**}·그 밖의 경로는 토큰이 맞아도 없는 경로와 같은 404 바이트다(인증보다 앞 — 401도 없다). 내부 포트로 온 공개 경로는 공개 거부 바이트.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PortSeparationIT {

    static final String T = SeedData.uniqueTenant("PORT");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void seed() {
        ApiTestSupport.DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.roleLink(c, T, "scheduler-1", "SCHEDULER");
            SeedData.roleLink(c, T, "compliance-1", "COMPLIANCE");
        });
    }

    @Value("${local.server.port}")
    int port;

    @Value("${local.internal.port}")
    int internal;

    @Test
    void eachPrefixAnswersOnlyOnItsPort() {
        assertThat(internal).isPositive().isNotEqualTo(port);
        String scheduler = TestJwts.token(T, "scheduler-1");
        String compliance = TestJwts.token(T, "compliance-1");
        ApiTestSupport.Response unrouted = sendExact(port, "GET", "/no-such-route", null, null, Map.of());
        assertThat(unrouted.status()).isEqualTo(404);

        assertThat(sendExact(internal, "GET", "/internal/v1/jobs", scheduler, null, Map.of()).status()).isEqualTo(200);
        assertThat(sendExact(port, "GET", "/api/v1/jobs", compliance, null, Map.of()).status()).isEqualTo(200);

        for (ApiTestSupport.Response wrongPort : java.util.List.of(
                sendExact(port, "GET", "/internal/v1/jobs", scheduler, null, Map.of()),
                sendExact(port, "GET", "/internal/v1/jobs", null, null, Map.of()),
                sendExact(port, "POST", "/internal/v1/jobs/NOTIFY", scheduler, "{}", Map.of("Idempotency-Key", "port-1")),
                sendExact(internal, "GET", "/api/v1/jobs", compliance, null, Map.of()),
                sendExact(internal, "GET", "/api/v1/jobs", null, null, Map.of()),
                sendExact(internal, "GET", "/", null, null, Map.of()),
                sendExact(internal, "GET", "/actuator/health", null, null, Map.of()))) {
            assertThat(wrongPort.fingerprint()).isEqualTo(unrouted.fingerprint());
        }
    }

    @Test
    void publicPathsOnTheInternalPortAreThePublicRejection() {
        ApiTestSupport.Response onPublic = sendExact(port, "POST", "/public/v1/sign/status", null, "{}", Map.of("X-Sign-Token", "not-a-token"));
        ApiTestSupport.Response onInternal = sendExact(internal, "POST", "/public/v1/sign/status", null, "{}", Map.of("X-Sign-Token", "not-a-token"));
        assertThat(onPublic.status()).isEqualTo(404);
        assertThat(onInternal.fingerprint()).isEqualTo(onPublic.fingerprint());
    }
}
