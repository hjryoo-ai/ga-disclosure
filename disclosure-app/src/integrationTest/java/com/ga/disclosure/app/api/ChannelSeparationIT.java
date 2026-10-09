package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static com.ga.disclosure.app.api.ApiTestSupport.get;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채널 분리(6A 승인 Q15, G11)와 인증 실패(계획 §4.2): 사람 역할 → {@code /internal}, 서비스 주체 → {@code /api}는 404이고, 그 바이트(상태·헤더 − Date·본문)가
 * 없는 라우트·없는 자원의 404와 같다. 토큰 없음·서명 불일치·발급자·대상·만료·테넌트 클레임 없음·형식 오류·모르는 테넌트는 전부 같은 401 바이트.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelSeparationIT {

    static final String TENANT = SeedData.uniqueTenant("CHAN");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void seed() {
        DB.seed(TENANT, c -> {
            SeedData.tenant(c, TENANT);
            SeedData.identityLink(c, TENANT, "agent-1", "AGENT-1", "AGENT");
            SeedData.roleLink(c, TENANT, "compliance-1", "COMPLIANCE");
            SeedData.roleLink(c, TENANT, "scheduler-1", "SCHEDULER");
            SeedData.roleLink(c, TENANT, "feed-1", "FEED_CONSUMER");
        });
    }

    @Value("${local.server.port}")
    int port;

    String token(String subject) {
        return TestJwts.token(TENANT, subject);
    }

    @Test
    void eachRoleReachesOnlyItsPrefixAndEverythingElseIsTheSame404() {
        assertThat(get(port, "/api/v1/jobs", token("compliance-1")).status()).isEqualTo(200);
        assertThat(get(port, "/internal/v1/jobs", token("scheduler-1")).status()).isEqualTo(200);

        ApiTestSupport.Response unknownRoute = get(port, "/api/v1/no-such-route", token("compliance-1"));
        assertThat(unknownRoute.status()).isEqualTo(404);
        assertThat(unknownRoute.text()).isEqualTo("{\"code\":\"NOT_FOUND\",\"details\":{},\"message\":\"Resource not found.\"}");
        List<ApiTestSupport.Response> denied = List.of(
                get(port, "/internal/v1/jobs", token("compliance-1")),              // 사람 역할 → /internal
                get(port, "/internal/v1/jobs", token("agent-1")),
                get(port, "/api/v1/jobs", token("scheduler-1")),                    // 서비스 주체 → /api
                get(port, "/api/v1/jobs", token("feed-1")),
                get(port, "/api/v1/jobs", token("agent-1")),                        // 역할 칸 없음
                get(port, "/api/v1/jobs/" + java.util.UUID.randomUUID(), token("compliance-1")),   // 없는 자원
                get(port, "/api/v1/jobs/not-a-uuid", token("compliance-1")),
                get(port, "/internal/v1/no-such-route", token("scheduler-1")),
                // 접두를 다른 표기로 써도(퍼센트 인코딩·중복 슬래시·세미콜론) 사람 역할이 /internal 핸들러에 닿지 못한다 — 채널은 라우팅되는 경로로 정한다
                get(port, "/%69nternal/v1/jobs", token("compliance-1")),
                get(port, "/inter%6eal/v1/jobs", token("compliance-1")),
                get(port, "/%61pi/v1/jobs", token("scheduler-1")));
        // 세미콜론 경로는 방화벽이 처리 전에 거부한다 — 같은 404 본문(핸들러에 닿지 않음), 보안 헤더는 붙지 않는다(관찰: 보고서)
        ApiTestSupport.Response semicolon = get(port, "/internal;x=1/v1/jobs", token("compliance-1"));
        assertThat(semicolon.status()).isEqualTo(404);
        assertThat(semicolon.text()).isEqualTo(unknownRoute.text());
        for (int i = 0; i < denied.size(); i++) {
            assertThat(denied.get(i).fingerprint()).as("case " + i + ": " + denied.get(i).status() + " " + denied.get(i).text())
                    .isEqualTo(unknownRoute.fingerprint());
        }
    }

    @Test
    void everyAuthenticationFailureIsTheSame401() {
        String wrongTenantFormat = TestJwts.sign(false, Map.of("tenant_id", "lower-case"), "compliance-1", TestJwts.ISSUER, TestJwts.AUDIENCE,
                Duration.ofMinutes(5));
        List<ApiTestSupport.Response> failures = List.of(
                get(port, "/api/v1/jobs", null),
                get(port, "/api/v1/jobs", "not.a.jwt"),
                get(port, "/api/v1/jobs", TestJwts.sign(true, Map.of("tenant_id", TENANT), "compliance-1", TestJwts.ISSUER, TestJwts.AUDIENCE,
                        Duration.ofMinutes(5))),                                          // 다른 키
                get(port, "/api/v1/jobs", TestJwts.sign(false, Map.of("tenant_id", TENANT), "compliance-1", "someone-else", TestJwts.AUDIENCE,
                        Duration.ofMinutes(5))),                                          // 발급자
                get(port, "/api/v1/jobs", TestJwts.sign(false, Map.of("tenant_id", TENANT), "compliance-1", TestJwts.ISSUER, "other-api",
                        Duration.ofMinutes(5))),                                          // 대상
                get(port, "/api/v1/jobs", TestJwts.sign(false, Map.of("tenant_id", TENANT), "compliance-1", TestJwts.ISSUER, TestJwts.AUDIENCE,
                        Duration.ofMinutes(-5))),                                         // 만료
                get(port, "/api/v1/jobs", TestJwts.sign(false, Map.of(), "compliance-1", TestJwts.ISSUER, TestJwts.AUDIENCE, Duration.ofMinutes(5))),
                get(port, "/api/v1/jobs", wrongTenantFormat),
                get(port, "/api/v1/jobs", TestJwts.token(SeedData.uniqueTenant("GHOST"), "compliance-1")),   // 모르는 테넌트
                get(port, "/internal/v1/no-such-route", null));
        ApiTestSupport.Response first = failures.getFirst();
        assertThat(first.status()).isEqualTo(401);
        assertThat(first.headers()).containsEntry("www-authenticate", "Bearer").doesNotContainKey("set-cookie");
        assertThat(first.text()).isEqualTo("{\"code\":\"UNAUTHENTICATED\",\"details\":{},\"message\":\"Authentication required.\"}");
        assertThat(failures).allSatisfy(r -> assertThat(r.fingerprint()).isEqualTo(first.fingerprint()));
    }
}
