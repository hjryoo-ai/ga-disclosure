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
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고객 공개 서명 경로(6A 계획 §5.1)를 HTTP로: 현장 기기 토큰(헤더 또는 본문)으로 상태 → 이른 서명은 업무 거부 422(유효 토큰 보유자) → 봉인 PDF → 열람 기록 →
 * (설계사 대면 확인) → 상태 → 서명 200 → 같은 토큰은 이제 거부(404 같은 바이트). 공개 응답은 쿠키 없음·리퍼러 없음·캐시 없음.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicSignFlowIT {

    static final String T = SeedData.uniqueTenant("PUB");
    static String customerRef;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void prepare() {
        customerRef = FlowSupport.prepare(T);
    }

    @Value("${local.server.port}")
    int port;

    static JsonNode json(ApiTestSupport.Response r) {
        return Canonicalizer.parseStrict(r.text());
    }

    String capture() {
        return "{\"strokes\":" + FlowSupport.STROKES + ",\"imagePngBase64\":\"" + Base64.getEncoder().encodeToString(FlowSupport.png()) + "\"}";
    }

    @Test
    void aCustomerViewsAndSignsWithADeviceToken() {
        String id = FlowSupport.sealed(port, T, customerRef);
        String token = FlowSupport.deviceToken(port, T, id, "TOUCH_PAD");

        ApiTestSupport.Response status = FlowSupport.publicPost(port, "/public/v1/sign/status", token, null);
        assertThat(status.status()).as(status.text()).isEqualTo(200);
        assertThat(json(status).get("identityRequired").toString()).isEqualTo("[\"AGENT_FACE_TO_FACE\",\"SCROLL_COMPLETE\"]");
        assertThat(json(status).get("identityPassed")).isEmpty();
        assertThat(status.headers()).doesNotContainKey("set-cookie").containsEntry("referrer-policy", "no-referrer");
        assertThat(status.headers().get("cache-control")).contains("no-store");
        assertThat(status.text()).doesNotContain(token);

        ApiTestSupport.Response early = FlowSupport.publicPost(port, "/public/v1/sign/capture", token, capture());
        assertThat(early.status()).as("valid token holder, business rejection").isEqualTo(422);
        assertThat(early.text()).contains("IDENTITY_INCOMPLETE");

        ApiTestSupport.Response pdf = FlowSupport.publicPost(port, "/public/v1/sign/open", token, null);
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        // 토큰은 본문으로도 된다(헤더가 없을 때)
        ApiTestSupport.Response viewed = FlowSupport.publicPost(port, "/public/v1/sign/view", null,
                "{\"token\":\"" + token + "\",\"scrollComplete\":true,\"viewSeconds\":31}");
        assertThat(viewed.status()).as(viewed.text()).isEqualTo(200);
        assertThat(FlowSupport.post(port, T, "agent-1", "/api/v1/sign-sessions/face-to-face", "{\"token\":\"" + token + "\"}").status()).isEqualTo(200);
        assertThat(json(FlowSupport.publicPost(port, "/public/v1/sign/status", token, null)).get("identityPassed").toString())
                .isEqualTo("[\"AGENT_FACE_TO_FACE\",\"SCROLL_COMPLETE\"]");

        ApiTestSupport.Response signed = FlowSupport.publicPost(port, "/public/v1/sign/capture", token, capture());
        assertThat(signed.status()).as(signed.text()).isEqualTo(200);
        assertThat(json(signed).get("signatureId").asString()).matches("[0-9a-f-]{36}");

        ApiTestSupport.Response used = FlowSupport.publicPost(port, "/public/v1/sign/status", token, null);
        assertThat(used.status()).isEqualTo(404);
        assertThat(used.text()).isEqualTo("{\"code\":\"SIGN_LINK_UNAVAILABLE\",\"details\":{},\"message\":\"This signing link cannot be used.\"}");
    }
}
