package com.ga.disclosure.app.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.PiiSentinels;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Channel;
import com.ga.disclosure.workflow.disclosure.NotificationDispatcher;
import com.ga.disclosure.workflow.sign.NotifyPort;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G6(6A 계획 §9.3): 센티널 고객(성명·번호·생년월일 — 허구 값, 파일로만 등록)으로 공개 서명 경로 전 흐름 — 원격 링크 발급·발송(번호 복호화)·상태·PDF·열람·
 * 본인확인(틀린 값, 맞는 센티널 생년월일)·서명·사용 뒤 거부, 현장 기기 토큰을 헤더·본문·질의·경로로, 틀린 비밀 — 을 돈 뒤 다음에 센티널 14종과 실행 중 생긴
 * 토큰 비밀(동적 센티널)이 0건이다: 루트 TRACE 로그, {@code ga.access} 로그, 표준 출력·오류, 공개 응답(헤더·본문, PDF 제외 — 성명을 담는 봉인 산출물),
 * 내부 응답(헤더·본문, PDF와 현장 기기 토큰을 전달하는 발급 응답 한 건 제외 — 그 응답은 no-store), DB 전체 덤프(감사·아웃박스·{@code notification_outbox} 포함), DB 서버 로그.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PublicPlaintextLeakScanIT.Hooks.class)
class PublicPlaintextLeakScanIT {

    static final String T = SeedData.uniqueTenant("PLK");
    static final List<String> LINKS = new CopyOnWriteArrayList<>();
    static String sentinelRef;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @TestConfiguration
    static class Hooks {
        /** 원격 링크를 받아 둔다(번호는 보지 않는다 — 어댑터 인자로만 지나간다). */
        @Bean
        @Primary
        NotifyPort capturingNotify() {
            return (to, link) -> LINKS.add(link.reveal());
        }
    }

    /** 센티널 고객은 임시 파일로만 넘긴다(절대 규칙 6 — CLI 인자·환경변수에 값이 없다). 파일은 등록 뒤 지운다. */
    @BeforeAll
    static void prepare() throws IOException {
        FlowSupport.prepare(T);
        Path dir = Files.createTempDirectory("ga-leak");
        Path file = dir.resolve("sentinel-customers.json");
        ObjectNode root = JsonMapper.builder().build().createObjectNode().put("schemaVersion", 1).put("source", "leak");
        root.putArray("customers").addObject().put("id", "S01").put("name", PiiSentinels.NAME).put("phone", PiiSentinels.PHONE)
                .put("birthDate", PiiSentinels.BIRTH_DATE);
        Files.writeString(file, root.toString());
        try {
            ApiTestSupport.cli("customer", "import", "--tenant", T, "--file", file.toString(), "--operator", "leak-it");
        } finally {
            Files.delete(file);
            Files.delete(dir);
        }
        sentinelRef = DB.asApp(T, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT customer_ref FROM customer_ref WHERE registration_key = ?")) {
                ps.setString(1, "leak:sentinel-customers.json#S01");
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getString(1);
                }
            }
        });
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    NotificationDispatcher dispatcher;

    static String secretOf(String token) {
        return token.substring(token.indexOf('~') + 1);
    }

    static String capture() {
        return "{\"strokes\":" + FlowSupport.STROKES + ",\"imagePngBase64\":\"" + Base64.getEncoder().encodeToString(FlowSupport.png()) + "\"}";
    }

    /** 금지 값(정적 센티널 + 동적 토큰 비밀) 가운데 {@code text}에 있는 것의 이름(값은 싣지 않는다). */
    static List<String> hits(String text, List<String> secrets) {
        List<String> found = new ArrayList<>();
        List<String> sentinels = PiiSentinels.forbidden();
        for (int i = 0; i < sentinels.size(); i++) {
            if (text.contains(sentinels.get(i))) {
                found.add("sentinel#" + i);
            }
        }
        for (int i = 0; i < secrets.size(); i++) {
            if (text.contains(secrets.get(i))) {
                found.add("token-secret#" + i);
            }
        }
        return found;
    }

    @Test
    void thePublicSigningPathLeaksNeitherCustomerValuesNorTokens() {
        LoggerContext logging = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = logging.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.TRACE);
        logging.getLogger("org.springframework.jdbc").setLevel(Level.TRACE);
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream streams = new ByteArrayOutputStream();
        PrintStream tee = new PrintStream(streams, true, StandardCharsets.UTF_8);
        List<String> publicResponses = new CopyOnWriteArrayList<>();
        List<String> internalResponses = new CopyOnWriteArrayList<>();
        List<String> secrets = new ArrayList<>();
        int[] pdfs = {0};
        int[] deliveries = {0};
        ApiTestSupport.observer = (path, r) -> {
            if ("application/pdf".equals(r.headers().get("content-type"))) {
                pdfs[0]++;
                return;
            }
            if (path.endsWith("/sign-sessions") && r.status() == 201 && Canonicalizer.parseStrict(r.text()).path("deviceToken").isString()) {
                // 현장 기기 토큰이 전달되는 단 하나의 응답(설계상 — no-store, 멱등 재생 없음). 그 밖의 응답은 전부 스캔한다
                assertThat(r.headers().get("cache-control")).contains("no-store");
                deliveries[0]++;
                return;
            }
            // 응답만(헤더·본문) — 요청 경로는 시험이 일부러 토큰을 넣은 입력이다
            (path.startsWith("/public/") ? publicResponses : internalResponses).add(r.headers() + "\n" + r.text());
        };
        System.setOut(tee);
        System.setErr(tee);
        try {
            // 원격 링크: 발급 → 발송(번호 복호화 → 어댑터, 토큰은 프래그먼트) → 고객
            String remoteId = FlowSupport.sealed(port, T, sentinelRef);
            ApiTestSupport.Response issued = FlowSupport.post(port, T, "agent-1", "/api/v1/disclosures/" + remoteId + "/sign-sessions",
                    "{\"channel\":\"REMOTE_LINK\"}");
            assertThat(issued.status()).as(issued.text()).isEqualTo(201);
            LINKS.clear();
            dispatcher.run(new Caller(TenantId.of(T), "leak-it", Channel.CLI), 10);
            assertThat(LINKS).hasSize(1);
            String remote = LINKS.getFirst().substring(LINKS.getFirst().indexOf('#') + 1);
            secrets.add(secretOf(remote));

            assertThat(FlowSupport.publicPost(port, "/public/v1/sign/status", remote, null).status()).isEqualTo(200);
            assertThat(FlowSupport.publicPost(port, "/public/v1/sign/open", remote, null).status()).isEqualTo(200);
            assertThat(FlowSupport.publicPost(port, "/public/v1/sign/view", remote, "{\"scrollComplete\":true,\"viewSeconds\":40}").status())
                    .isEqualTo(200);
            ApiTestSupport.Response wrong = FlowSupport.publicPost(port, "/public/v1/sign/verify-identity", remote, "{\"birthDate\":\"1999-12-31\"}");
            assertThat(Canonicalizer.parseStrict(wrong.text()).get("passed").asBoolean()).isFalse();
            ApiTestSupport.Response right = FlowSupport.publicPost(port, "/public/v1/sign/verify-identity", null,
                    "{\"token\":\"" + remote + "\",\"birthDate\":\"" + PiiSentinels.BIRTH_DATE + "\"}");
            assertThat(Canonicalizer.parseStrict(right.text()).get("passed").asBoolean()).as(right.text()).isTrue();
            ApiTestSupport.Response signed = FlowSupport.publicPost(port, "/public/v1/sign/capture", remote, capture());
            assertThat(signed.status()).as(signed.text()).isEqualTo(200);
            assertThat(FlowSupport.publicPost(port, "/public/v1/sign/status", remote, null).status()).as("used").isEqualTo(404);

            // 현장 기기 토큰: 헤더·본문·질의·경로·틀린 비밀, 설계사 대면 확인(내부 경로 본문)
            String device = FlowSupport.deviceToken(port, T, FlowSupport.sealed(port, T, sentinelRef), "TOUCH_PAD");
            secrets.add(secretOf(device));
            assertThat(FlowSupport.publicPost(port, "/public/v1/sign/status", device, null).status()).isEqualTo(200);
            assertThat(FlowSupport.publicPost(port, "/public/v1/sign/status", null, "{\"token\":\"" + device + "\"}").status()).isEqualTo(200);
            assertThat(FlowSupport.publicPost(port, "/public/v1/sign/status?token=" + device, null, null).status()).isEqualTo(404);
            assertThat(FlowSupport.publicPost(port, "/public/v1/sign/status/" + device, null, null).status()).isEqualTo(404);
            assertThat(ApiTestSupport.send(port, "GET", "/public/v1/sign/" + device, null, null, Map.of()).status()).isEqualTo(404);
            String flipped = device.substring(0, device.length() - 1) + (device.endsWith("A") ? "B" : "A");
            assertThat(FlowSupport.publicPost(port, "/public/v1/sign/status", flipped, null).status()).isEqualTo(404);
            assertThat(FlowSupport.publicPost(port, "/public/v1/sign/capture", device, capture()).status()).as("identity incomplete").isEqualTo(422);
            assertThat(FlowSupport.post(port, T, "agent-1", "/api/v1/sign-sessions/face-to-face", "{\"token\":\"" + device + "\"}").status())
                    .isEqualTo(200);
            assertThat(ApiTestSupport.get(port, "/api/v1/disclosures/" + remoteId, TestJwts.token(T, "agent-1")).status()).isEqualTo(200);
        } finally {
            ApiTestSupport.observer = null;
            System.setOut(out);
            System.setErr(err);
            root.detachAppender(appender);
            root.setLevel(previous);
            logging.getLogger("org.springframework.jdbc").setLevel(null);
        }

        List<String> logLines = appender.list.stream().map(e -> e.getLoggerName() + " " + e.getFormattedMessage() + " "
                + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage())).toList();
        List<String> access = logLines.stream().filter(l -> l.startsWith("ga.access ")).toList();
        assertThat(logLines).as("TRACE logging actually captured JDBC activity").anyMatch(l -> l.contains("sign_session"));
        assertThat(access).as("the access log saw the public calls by template")
                .anyMatch(l -> l.contains("POST /public/v1/sign/status 200"))
                .anyMatch(l -> l.contains("POST /public/v1/sign/capture 200"))
                .anyMatch(l -> l.contains("/public/** 404"));
        assertThat(publicResponses).hasSizeGreaterThan(10);
        assertThat(internalResponses).hasSizeGreaterThan(10);
        assertThat(pdfs[0]).as("the signing PDF was fetched (and excluded from the scan)").isEqualTo(1);
        assertThat(deliveries[0]).as("the device token's own issuance response (excluded from the scan)").isEqualTo(1);
        assertThat(secrets).hasSize(2).allSatisfy(s -> assertThat(s).hasSizeGreaterThan(20));

        assertThat(hits(String.join("\n", access), secrets)).as("ga.access").isEmpty();
        assertThat(hits(String.join("\n", logLines), secrets)).as("root TRACE log").isEmpty();
        assertThat(hits(streams.toString(StandardCharsets.UTF_8), secrets)).as("stdout/stderr").isEmpty();
        assertThat(hits(String.join("\n", publicResponses), secrets)).as("public responses").isEmpty();
        assertThat(hits(String.join("\n", internalResponses), secrets)).as("internal responses").isEmpty();
        String dump = DB.dumpAllData();
        assertThat(dump).as("the dump has this run's audit, outbox and notification rows").contains("SIGN_SESSION_SEND").contains("notification_outbox")
                .contains(T);
        assertThat(hits(dump, secrets)).as("database dump (audit, outbox, notification_outbox, ...)").isEmpty();
        assertThat(hits(DB.serverLogs(), secrets)).as("database server log").isEmpty();
    }
}
