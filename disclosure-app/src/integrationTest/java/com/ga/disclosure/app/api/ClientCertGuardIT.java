package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.app.config.ClientCertHeaderGuard;
import com.ga.disclosure.compliance.rules.RuleActivationJob;
import com.ga.disclosure.compliance.rules.RuleDistributionService;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6B 계획 §6 Q11 mTLS 주체 대조: 설정 {@code ga.api.client-cert.subject-header}가 켜지면 게이트·계약 연결 경로는 인그레스가 넣은 그 헤더가 토큰 주체와
 * 같을 때만 열린다 — 없거나 다르면 없는 라우트와 같은 404(판정 감사 없음). 다른 경로는 그대로다. {@code prod} 프로파일은 설정 없이 기동하지 않는다.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientCertGuardIT {

    static final String HEADER = "X-Client-Cert-Subject";
    static final String GATE = "gate-client-1";
    static final String FEED = "contract-feed-1";
    static final String SCHEDULER = "scheduler-1";
    static final String CUSTOMER = "CR-" + "3".repeat(32);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
        registry.add(ClientCertHeaderGuard.PROPERTY, () -> HEADER);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    RuleDistributionService distribution;

    @Autowired
    RuleActivationJob activation;

    ApiTestSupport.Response post(String t, String subject, String path, String json, Map<String, String> headers) {
        return ApiTestSupport.post(port, path, TestJwts.token(t, subject), json, headers);
    }

    long gateAudits(String t) {
        return Long.parseLong(DB.asApp(t, c -> SeedData.call(c, "SELECT count(*)::text FROM audit_log WHERE tenant_id = ? AND action = 'GATE_DECISION'", t)));
    }

    @Test
    void theGuardedPathsOpenOnlyWhenTheCertificateSubjectIsTheTokenSubject() {
        String t = SeedData.uniqueTenant("MTLS");
        DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.roleLink(c, t, GATE, "GATE_CLIENT");
            SeedData.roleLink(c, t, SCHEDULER, "SCHEDULER");
            SeedData.feedLink(c, t, FEED, "INS_FEED_A");
        });
        ApiTestSupport.activateRules(distribution, activation, t, null, body -> {
        });
        String query = "{\"policyNo\":\"POL-MTLS\",\"customerRef\":\"" + CUSTOMER + "\"}";
        ApiTestSupport.Response unrouted = post(t, GATE, "/internal/v1/no-such-route", "{}", Map.of());

        for (Map<String, String> wrong : java.util.List.<Map<String, String>>of(Map.of(), Map.of(HEADER, "someone-else"), Map.of(HEADER, FEED))) {
            ApiTestSupport.Response denied = post(t, GATE, "/internal/v1/gate", query, wrong);
            assertThat(denied.status()).as(wrong.toString()).isEqualTo(404);
            assertThat(denied.fingerprint()).as(wrong.toString()).isEqualTo(unrouted.fingerprint());
        }
        // 경로 표기를 바꿔도(퍼센트 인코딩·세미콜론·끝 슬래시·대소문자 무관 문자) 대조를 건너뛰어 게이트에 닿지 못한다 — 보안 검토 반영
        for (String variant : new String[] {"/internal/v1/%67ate", "/internal/v1/g%61te", "/internal/%761/gate", "/internal/v1/gate;x=1",
                "/internal/v1/gate/"}) {
            ApiTestSupport.Response r = post(t, GATE, variant, query, Map.of());
            assertThat(r.status()).as(variant).isNotEqualTo(200);
        }
        assertThat(gateAudits(t)).isZero();
        assertThat(post(t, GATE, "/internal/v1/gate", query, Map.of(HEADER, GATE)).status()).isEqualTo(200);
        assertThat(gateAudits(t)).isOne();

        // 계약 연결 입구도 같다
        String batch = "{\"schemaVersion\":1,\"source\":\"INS_FEED_A\",\"batchId\":\"MTLS-1\",\"items\":[{\"policyNo\":\"POL-MTLS\","
                + "\"contractDate\":\"2026-09-30\",\"insurerCode\":\"INS-A\"}]}";
        Map<String, String> key = Map.of("Idempotency-Key", "it-" + UUID.randomUUID());
        ApiTestSupport.Response feedDenied = post(t, FEED, "/internal/v1/contract-links", batch, key);
        assertThat(feedDenied.status()).isEqualTo(404);
        assertThat(feedDenied.fingerprint()).isEqualTo(unrouted.fingerprint());
        assertThat(post(t, FEED, "/internal/v1/contract-links", batch, Map.of("Idempotency-Key", "it-" + UUID.randomUUID(), HEADER, FEED)).status())
                .isEqualTo(202);

        // 그 밖의 경로는 헤더와 무관하다
        assertThat(ApiTestSupport.get(port, "/internal/v1/jobs", TestJwts.token(t, SCHEDULER)).status()).isEqualTo(200);
    }

    @Test
    void theProdProfileDoesNotStartWithoutTheHeaderSetting() {
        assertThat(ClientCertHeaderGuard.class.getAnnotation(Profile.class).value()).containsExactly("prod");
        assertThatThrownBy(() -> new ClientCertHeaderGuard(new MockEnvironment())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ClientCertHeaderGuard.PROPERTY);
        assertThatThrownBy(() -> new ClientCertHeaderGuard(new MockEnvironment().withProperty(ClientCertHeaderGuard.PROPERTY, " ")))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new ClientCertHeaderGuard(new MockEnvironment().withProperty(ClientCertHeaderGuard.PROPERTY, HEADER))).doesNotThrowAnyException();
    }
}
