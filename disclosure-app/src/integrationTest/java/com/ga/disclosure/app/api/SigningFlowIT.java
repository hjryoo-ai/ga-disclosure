package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.sign.DeviceInfo;
import com.ga.disclosure.workflow.sign.SignatureCapture;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서명 경로(6A 계획 §4.1)를 HTTP로: 현장 기기 세션 발급(토큰은 응답에만 — {@code no-store}, 같은 키 재요청은 409 {@code IDEMPOTENCY_NOT_REPLAYABLE}이고
 * 멱등 행에 토큰이 없다) → 대면 확인 → (고객: 열람·서명 — 공개 경로는 7단계라 유스케이스로) → 설계사 서명 → 관리자 확인(완료까지). 순서 위반·중복 서명·완료 뒤
 * 완료는 409, 종이 스캔 번호 불일치는 422.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SigningFlowIT {

    static final String T = SeedData.uniqueTenant("SIGN");
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

    @Autowired
    SignSessionService sessions;

    @Autowired
    SignService signing;

    ApiTestSupport.Response post(String subject, String path, String json) {
        return FlowSupport.post(port, T, subject, path, json);
    }

    static JsonNode json(ApiTestSupport.Response r) {
        return Canonicalizer.parseStrict(r.text());
    }

    String agentSignature() {
        return "{\"strokes\":" + FlowSupport.STROKES + ",\"imagePngBase64\":\"" + Base64.getEncoder().encodeToString(FlowSupport.png()) + "\"}";
    }

    /**
     * 원격 링크 영수증에는 일회용 자격이 없다 — 컨트롤러가 no-store를 걸지 않으므로 같은 키는 같은 바이트로 재생된다(효과 1회). (응답의 캐시 헤더는 보안
     * 체인 기본값이 모든 응답에 붙인다 — 멱등 인터셉터가 보는 것은 컨트롤러가 직접 건 no-store뿐이다.)
     */
    @Test
    void aRemoteLinkIssuanceReplaysLikeAnyOtherWrite() {
        String base = "/api/v1/disclosures/" + FlowSupport.sealed(port, T, customerRef);
        String key = "sess-" + UUID.randomUUID();
        ApiTestSupport.Response issued = ApiTestSupport.post(port, base + "/sign-sessions", TestJwts.token(T, "agent-1"), "{\"channel\":\"REMOTE_LINK\"}",
                Map.of("Idempotency-Key", key));
        assertThat(issued.status()).as(issued.text()).isEqualTo(201);
        assertThat(json(issued).get("deviceToken").isNull()).isTrue();
        assertThat(json(issued).get("signUrl").isNull()).as("a remote link token never reaches the screen").isTrue();
        ApiTestSupport.Response again = ApiTestSupport.post(port, base + "/sign-sessions", TestJwts.token(T, "agent-1"), "{\"channel\":\"REMOTE_LINK\"}",
                Map.of("Idempotency-Key", key));
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.text()).isEqualTo(issued.text());
        assertThat(again.headers()).containsEntry("idempotency-replayed", "true");
        long open = DB.asApp(T, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM sign_session WHERE session_id = ?::uuid")) {
                ps.setString(1, json(issued).get("sessionId").asString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertThat(open).as("one session — the replay had no effect").isEqualTo(1);
    }

    @Test
    void touchPadSessionThroughCompletion() {
        String id = FlowSupport.sealed(port, T, customerRef);
        String base = "/api/v1/disclosures/" + id;
        String key = "sess-" + UUID.randomUUID();
        ApiTestSupport.Response issued = ApiTestSupport.post(port, base + "/sign-sessions", TestJwts.token(T, "agent-1"), "{\"channel\":\"TOUCH_PAD\"}",
                Map.of("Idempotency-Key", key));
        assertThat(issued.status()).as(issued.text()).isEqualTo(201);
        assertThat(issued.headers().get("cache-control")).contains("no-store");
        String token = json(issued).get("deviceToken").asString();
        assertThat(token).isNotBlank();
        // Phase 8: 서명 창 주소는 배포 설정의 서명 호스트(이 시험은 기본값) + 조각 토큰 — 화면은 이 주소를 연다
        assertThat(json(issued).get("signUrl").asString()).isEqualTo("https://sign.example.invalid/s#" + token);

        ApiTestSupport.Response again = ApiTestSupport.post(port, base + "/sign-sessions", TestJwts.token(T, "agent-1"), "{\"channel\":\"TOUCH_PAD\"}",
                Map.of("Idempotency-Key", key));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.text()).contains("\"code\":\"IDEMPOTENCY_NOT_REPLAYABLE\"").doesNotContain(token);
        String stored = DB.asApp(T, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT response_status || ' ' || response_ref::text FROM idempotency_key WHERE idem_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        assertThat(stored).startsWith("409 ").contains("IDEMPOTENCY_NOT_REPLAYABLE").doesNotContain(token);
        assertThat(post("agent-x", base + "/sign-sessions", "{\"channel\":\"TOUCH_PAD\"}").status()).as("another agent").isEqualTo(404);

        ApiTestSupport.Response faceToFace = post("agent-1", "/api/v1/sign-sessions/face-to-face", "{\"token\":\"" + token + "\"}");
        assertThat(faceToFace.status()).as(faceToFace.text()).isEqualTo(200);
        assertThat(faceToFace.text()).doesNotContain(token);

        // 고객 쪽(공개 경로는 6A 7단계): 끝까지 열람 → 서명
        sessions.recordView(token, true, 30);
        signing.capture(token, new SignatureCapture(FlowSupport.STROKES.getBytes(StandardCharsets.UTF_8), FlowSupport.png(),
                new DeviceInfo(null, "Kiosk/1.0"), null));

        ApiTestSupport.Response early = post("manager-1", base + "/manager-confirmation", "{\"acknowledgedFlags\":[]}");
        assertThat(early.status()).as("sequential order — the agent signs first").isEqualTo(409);
        assertThat(early.text()).contains("ORDER_VIOLATION");

        ApiTestSupport.Response agent = post("agent-1", base + "/agent-signature", agentSignature());
        assertThat(agent.status()).as(agent.text()).isEqualTo(200);
        assertThat(json(agent).get("signatureId").asString()).matches("[0-9a-f-]{36}");
        assertThat(post("agent-1", base + "/agent-signature", agentSignature()).text()).contains("ALREADY_SIGNED");

        assertThat(post("manager-1", base + "/manager-confirmation", "{\"acknowledgedFlags\":[\"nope\"]}").text())
                .contains("\"field\":\"acknowledgedFlags\"");
        ApiTestSupport.Response confirmed = post("manager-1", base + "/manager-confirmation", "{\"acknowledgedFlags\":[]}");
        assertThat(confirmed.status()).as(confirmed.text()).isEqualTo(200);
        // 마지막 서명자(관리자)의 확인이 완료 판정까지 한다 — 따로 부른 완료는 상태 충돌
        assertThat(json(confirmed).get("completed").asBoolean()).isTrue();
        assertThat(json(confirmed).get("status").asString()).isEqualTo("COMPLETED");
        ApiTestSupport.Response completeAgain = post("agent-1", base + "/complete", null);
        assertThat(completeAgain.status()).isEqualTo(409);
    }

    @Test
    void paperScanMismatchIs422AndAMatchingScanSigns() {
        String id = FlowSupport.sealed(port, T, customerRef);
        String base = "/api/v1/disclosures/" + id;
        String token = json(post("agent-1", base + "/sign-sessions", "{\"channel\":\"PAPER_SCAN\"}")).get("deviceToken").asString();
        assertThat(post("agent-1", "/api/v1/sign-sessions/face-to-face", "{\"token\":\"" + token + "\"}").status()).isEqualTo(200);
        JsonNode detail = json(ApiTestSupport.get(port, base, TestJwts.token(T, "agent-1")));
        String number = detail.get("seal").get("disclosureNo").asString();
        String prefix = detail.get("seal").get("canonicalHash").asString().substring(0, 12);
        String image = Base64.getEncoder().encodeToString(FlowSupport.png());

        ApiTestSupport.Response mismatch = post("agent-1", "/api/v1/sign-sessions/paper-scan",
                "{\"token\":\"" + token + "\",\"disclosureNo\":\"" + T + "-2026-999999\",\"hashPrefix\":\"" + prefix + "\",\"imageBase64\":\"" + image + "\"}");
        assertThat(mismatch.status()).as(mismatch.text()).isEqualTo(422);
        assertThat(mismatch.text()).contains("SCAN_MISMATCH").doesNotContain(token);
        ApiTestSupport.Response scanned = post("agent-1", "/api/v1/sign-sessions/paper-scan",
                "{\"token\":\"" + token + "\",\"disclosureNo\":\"" + number + "\",\"hashPrefix\":\"" + prefix + "\",\"imageBase64\":\"" + image + "\"}");
        assertThat(scanned.status()).as(scanned.text()).isEqualTo(200);
        assertThat(json(scanned).get("signatureId").isNull()).isFalse();
        assertThat(post("agent-1", "/api/v1/sign-sessions/paper-scan", "{\"token\":\"" + token + "\",\"disclosureNo\":\"" + number
                + "\",\"hashPrefix\":\"" + prefix + "\",\"imageBase64\":\"!!\"}").text()).contains("\"field\":\"imageBase64\"");
    }
}
