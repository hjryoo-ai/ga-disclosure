package com.ga.disclosure.app.api;

import com.ga.disclosure.api.security.Sleeper;
import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Channel;
import com.ga.disclosure.workflow.disclosure.NotificationDispatcher;
import com.ga.disclosure.workflow.sign.NotifyPort;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5·G7·B2(6A 계획 §5.2~§5.4): 공개 서명 거부는 사유와 무관하게 같은 응답이다(상태·헤더 − Date·본문). 사유 7종 — ① 형식 ② 없는 테넌트 ③ 틀린 비밀 ④ 만료
 * ⑤ 취소(재발급) ⑥ 사용됨 ⑦ 본인확인 실패 한도 — 과 추가 둘(토큰을 질의로·경로로), 그리고 테넌트 분당 한도 초과. 모든 응답(성공 포함)은 패딩 훅을 정확히 한 번
 * 지나고 요청된 대기는 {@code max(0, 하한 − 경과)}다 — 없는 테넌트(카운터 전 거부)와 한도 초과(카운터에서 거부)도 같은 식(B2).
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PublicSignUniformResponseIT.Hooks.class)
class PublicSignUniformResponseIT {

    static final String T = SeedData.uniqueTenant("UNI");
    static final String LIMITED = SeedData.uniqueTenant("UNIL");
    static final int LIMIT = 3;
    static final Duration FLOOR = Duration.ofMillis(30);
    static final List<Duration[]> SLEEPS = new CopyOnWriteArrayList<>();
    static final List<String> LINKS = new CopyOnWriteArrayList<>();
    static String customer;
    static String limitedCustomer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @TestConfiguration
    static class Hooks {
        /** 기록형 대기(실제로 기다리지 않는다): {요청된 대기, 경과}. */
        @Bean
        @Primary
        Sleeper recordingSleeper() {
            return (requested, elapsed) -> SLEEPS.add(new Duration[] {requested, elapsed});
        }

        /** 원격 링크를 받아 둔다(번호는 보지 않는다). */
        @Bean
        @Primary
        NotifyPort capturingNotify() {
            return (to, link) -> LINKS.add(link.reveal());
        }
    }

    @BeforeAll
    static void prepare() {
        FlowSupport.prepare(T);
        customer = FlowSupport.customerRef(T, "C01");
        FlowSupport.prepare(LIMITED, FlowSupport.variantBundle("UNI-IT-RATE3", body -> ((ObjectNode) body.get("publicSign")).put("tenantRatePerMinute", LIMIT)));
        limitedCustomer = FlowSupport.customerRef(LIMITED, "C03");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    NotificationDispatcher dispatcher;

    @Autowired
    com.ga.disclosure.api.security.RateWindow rates;

    /** 공개 요청 한 건 + 패딩 훅이 정확히 한 번, 요청된 대기 = max(0, 하한 − 경과). */
    ApiTestSupport.Response call(String path, String tokenOrNull, String json) {
        SLEEPS.clear();
        ApiTestSupport.Response r = ApiTestSupport.send(port, "POST", path, null, json, tokenOrNull == null ? Map.of() : Map.of("X-Sign-Token", tokenOrNull));
        assertThat(SLEEPS).as("padding hook once for " + path).hasSize(1);
        Duration requested = SLEEPS.getFirst()[0];
        Duration elapsed = SLEEPS.getFirst()[1];
        Duration expected = FLOOR.minus(elapsed).isNegative() ? Duration.ZERO : FLOOR.minus(elapsed);
        assertThat(requested).as("requested wait = max(0, floor - elapsed)").isEqualTo(expected);
        return r;
    }

    static String otherSecret(String token) {
        int sep = token.indexOf('~');
        char last = token.charAt(token.length() - 1);
        return token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
    }

    String remoteLinkToken(String disclosureId) {
        ApiTestSupport.Response issued = FlowSupport.post(port, T, "agent-1", "/api/v1/disclosures/" + disclosureId + "/sign-sessions",
                "{\"channel\":\"REMOTE_LINK\"}");
        assertThat(issued.status()).as(issued.text()).isEqualTo(201);
        LINKS.clear();
        dispatcher.run(new Caller(TenantId.of(T), "uniform-it", Channel.CLI), 10);
        assertThat(LINKS).hasSize(1);
        return LINKS.getFirst().substring(LINKS.getFirst().indexOf('#') + 1);
    }

    @Test
    void everyRejectionIsTheSameResponseAndEveryResponseIsPaddedOnce() throws Exception {
        String sealed = FlowSupport.sealed(port, T, customer);
        Map<String, ApiTestSupport.Response> rejections = new LinkedHashMap<>();

        rejections.put("1 malformed", call("/public/v1/sign/status", "not-a-token", null));
        String valid = FlowSupport.deviceToken(port, T, sealed, "TOUCH_PAD");
        rejections.put("2 unknown tenant", call("/public/v1/sign/status", "NOPE_" + SeedData.uniqueTenant("X") + valid.substring(valid.indexOf('~')), null));
        rejections.put("3 wrong secret", call("/public/v1/sign/status", otherSecret(valid), null));

        String expiring = FlowSupport.deviceToken(port, T, FlowSupport.sealed(port, T, customer), "TOUCH_PAD");
        try (var c = DB.superuserDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            // 시간이 흐른 것처럼: 발급·만료를 함께 과거로(ck_sign_session_window — 만료는 발급 뒤)
            s.executeUpdate("UPDATE sign_session SET issued_at = now() - INTERVAL '2 hours', expires_at = now() - INTERVAL '1 minute'"
                    + " WHERE tenant_id = '" + T + "' AND status = 'OPEN'"
                    + " AND channel = 'TOUCH_PAD' AND disclosure_id <> '" + sealed + "'");
        }
        rejections.put("4 expired", call("/public/v1/sign/status", expiring, null));

        String reissued = FlowSupport.deviceToken(port, T, sealed, "TOUCH_PAD");   // 재발급이 valid를 닫는다
        rejections.put("5 cancelled", call("/public/v1/sign/status", valid, null));

        call("/public/v1/sign/view", reissued, "{\"scrollComplete\":true,\"viewSeconds\":12}");
        assertThat(FlowSupport.post(port, T, "agent-1", "/api/v1/sign-sessions/face-to-face", "{\"token\":\"" + reissued + "\"}").status()).isEqualTo(200);
        ApiTestSupport.Response signed = call("/public/v1/sign/capture", reissued, "{\"strokes\":" + FlowSupport.STROKES + ",\"imagePngBase64\":\""
                + Base64.getEncoder().encodeToString(FlowSupport.png()) + "\"}");
        assertThat(signed.status()).as(signed.text()).isEqualTo(200);
        rejections.put("6 used", call("/public/v1/sign/status", reissued, null));

        String remote = remoteLinkToken(FlowSupport.sealed(port, T, customer));
        for (int i = 0; i < 5; i++) {
            call("/public/v1/sign/verify-identity", remote, "{\"birthDate\":\"1999-12-31\"}");
        }
        rejections.put("7 identity failures exhausted", call("/public/v1/sign/status", remote, null));

        // 질의·경로·메서드 거부는 유효한 헤더 토큰이 있어도 같은 거부다(그 검사만으로 거부되는지 본다)
        String fresh = FlowSupport.deviceToken(port, T, FlowSupport.sealed(port, T, customer), "TOUCH_PAD");
        assertThat(call("/public/v1/sign/status", fresh, null).status()).as("the fresh token works").isEqualTo(200);
        rejections.put("token in the query", call("/public/v1/sign/status?token=" + fresh, fresh, null));
        rejections.put("token in the path", call("/public/v1/sign/status/" + fresh, fresh, null));
        rejections.put("GET instead of POST", ApiTestSupport.send(port, "GET", "/public/v1/sign/status", null, null, Map.of("X-Sign-Token", fresh)));

        ApiTestSupport.Response reference = rejections.get("1 malformed");
        assertThat(reference.status()).isEqualTo(404);
        assertThat(reference.text()).isEqualTo("{\"code\":\"SIGN_LINK_UNAVAILABLE\",\"details\":{},\"message\":\"This signing link cannot be used.\"}");
        assertThat(reference.headers()).doesNotContainKey("set-cookie").containsEntry("referrer-policy", "no-referrer");
        rejections.forEach((reason, r) -> assertThat(r.fingerprint()).as(reason).isEqualTo(reference.fingerprint()));
        assertThat(rejections.values().stream().map(ApiTestSupport.Response::text)).allSatisfy(t -> assertThat(t).doesNotContain(valid, reissued, remote, fresh));
    }

    @Test
    void theTenantRateLimitIsTheSameRejectionAndPaddedTheSameWay() throws Exception {
        // 고정 1분 창 — 창 경계에 걸리지 않게 분의 앞쪽에서 시작한다
        int second = Instant.now().atOffset(ZoneOffset.UTC).getSecond();
        if (second > 40) {
            Thread.sleep((61 - second) * 1000L);
        }
        String token = FlowSupport.deviceToken(port, LIMITED, FlowSupport.sealed(port, LIMITED, limitedCustomer), "TOUCH_PAD");
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) {
            statuses.add(call("/public/v1/sign/status", token, null).status());
        }
        assertThat(statuses).containsOnly(200);
        ApiTestSupport.Response limited = call("/public/v1/sign/status", token, null);
        String unknownTenantId = "NOPE_" + SeedData.uniqueTenant("Y");
        ApiTestSupport.Response unknownTenant = call("/public/v1/sign/status", unknownTenantId + token.substring(token.indexOf('~')), null);
        assertThat(limited.status()).isEqualTo(404);
        assertThat(limited.fingerprint()).isEqualTo(unknownTenant.fingerprint());
        // 없는 테넌트는 카운터의 키가 되지 않는다(맵을 키울 수 없다), 알려진 테넌트는 된다
        assertThat(rates.tracks(TenantId.of(LIMITED))).isTrue();
        assertThat(rates.tracks(TenantId.of(unknownTenantId))).isFalse();
    }
}
