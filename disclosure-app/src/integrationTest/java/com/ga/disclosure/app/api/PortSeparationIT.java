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

    /**
     * 보안 검토(Phase 8 3단계 뒤): 포트 대조는 라우팅과 같은 파서로 한다 — 원 URI 접두로 판정하면 퍼센트 인코딩·세미콜론·중복 슬래시 표기의 내부 경로가
     * 앱 포트에서 대조를 건너뛰고 MVC(디코딩한 경로로 매칭)를 통해 내부 핸들러에 닿는다(6B 8단계와 같은 부류 — 첫 판의 {@code /%69nternal/v1/jobs}가 200이었다).
     * 어떤 표기든 앱 포트에서는 없는 경로와 같은 404이거나, 앱 앞에서 컨테이너가 400으로 거부한다. 요청은 계약 검증 없이 원문 그대로 보낸다(정규화 금지).
     */
    @Test
    void noSpellingOfTheInternalPrefixReachesItsHandlersOnTheAppPort() {
        String scheduler = TestJwts.token(T, "scheduler-1");
        ApiTestSupport.Response unrouted = sendExact(port, "GET", "/no-such-route", null, null, Map.of());
        for (String spelling : java.util.List.of("/%69nternal/v1/jobs", "/internal%2Fv1/jobs", "/internal;x=1/v1/jobs", "/internal/v1;x=1/jobs",
                "//internal/v1/jobs", "/internal/./v1/jobs", "/api/../internal/v1/jobs", "/%2569nternal/v1/jobs", "/internal/v1/jobs/")) {
            ApiTestSupport.Response r = raw(port, spelling, scheduler);
            if (r.status() == 400) {
                // 컨테이너(Tomcat)가 앱 앞에서 거부하는 표기(인코딩된 '/' 등) — 어떤 핸들러에도 닿지 않는다
                assertThat(r.text()).as(spelling).doesNotContain("\"items\"").doesNotContain("NOT_FOUND");
            } else {
                // 404 본문은 없는 경로와 같다(세미콜론 표기는 보안 방화벽이 체인 앞에서 거부해 보안 헤더가 없다 — 포트와 무관한 기존 동작)
                assertThat(r.status()).as(spelling).isEqualTo(404);
                assertThat(r.text()).as(spelling).isEqualTo(unrouted.text());
            }
        }
    }

    /** 경로를 정규화하지 않고 그대로 보낸다(java.net.URI는 '..'·'.'을 남기지만 계약 검증기는 표기 변형을 모른다 — 응답만 본다). */
    private static ApiTestSupport.Response raw(int port, String path, String token) {
        try (java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient()) {
            java.net.http.HttpResponse<byte[]> r = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + token).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            java.util.Map<String, String> headers = new java.util.TreeMap<>();
            r.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), String.join(",", v)));
            return new ApiTestSupport.Response(r.statusCode(), headers, r.body());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
