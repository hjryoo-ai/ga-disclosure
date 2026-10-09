package com.ga.disclosure.app.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G9(6B 계획 §9.7), 승인 §4 조건 5·1: 고객 등록 API의 누출 스캔은 <b>동적 센티널</b>로 돈다 — 요청마다 무작위로 만든 이름·전화·생년월일 그 값 자체와 그
 * 표기(전화 하이픈 있음·없음, 생년월일 {@code yyyy-MM-dd}·{@code yyyyMMdd}, 각 UTF-8 hex)가 금지 문자열이다. 성공(첫 등록·재생·멱등 만료 뒤 NOOP)·형식
 * 400(필드별, 모르는 필드, 4 KiB 초과)·키 재사용 422·한도 429·권한 없음 404를 지난 뒤 다음에 0건: 응답(헤더·본문), 루트 TRACE 로그(JDBC TRACE 포함),
 * {@code ga.access}, 표준 출력·오류, DB 전체 덤프(감사·멱등·아웃박스·작업 — 고객 테이블은 암호문), DB 서버 로그.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiPlaintextLeakScanIT {

    static final String T = SeedData.uniqueTenant("ALK");
    static final String LIMITED = SeedData.uniqueTenant("ALKL");
    static final SecureRandom RANDOM = new SecureRandom();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void prepare() {
        FlowSupport.prepare(T);
        FlowSupport.prepare(LIMITED, FlowSupport.variantBundle("DISC-CUSTLIM-1", b -> ((ObjectNode) b.get("customers")).put("registerPerMinute", 1)));
    }

    @Value("${local.server.port}")
    int port;

    /** 한 요청의 실제 값(무작위). 이름은 한글 6음절, 전화는 010 + 8자리, 생년월일은 1930~2000년. */
    record Person(String name, String phoneDigits, LocalDate birth) {

        static Person random() {
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                name.appendCodePoint(0xAC00 + RANDOM.nextInt(0xD7A3 - 0xAC00 + 1));
            }
            StringBuilder phone = new StringBuilder("010");
            for (int i = 0; i < 8; i++) {
                phone.append(RANDOM.nextInt(10));
            }
            LocalDate birth = LocalDate.of(1930, 1, 1).plusDays(RANDOM.nextInt(365 * 70));
            return new Person(name.toString(), phone.toString(), birth);
        }

        String phoneHyphen() {
            return phoneDigits.substring(0, 3) + "-" + phoneDigits.substring(3, 7) + "-" + phoneDigits.substring(7);
        }

        String birthCompact() {
            return birth.toString().replace("-", "");
        }

        /** 이 사람의 금지 표기 전부 — 값 그대로와 UTF-8 hex. */
        List<String> forms() {
            List<String> plain = List.of(name, phoneDigits, phoneHyphen(), birth.toString(), birthCompact());
            List<String> all = new ArrayList<>(plain);
            plain.forEach(v -> all.add(HexFormat.of().formatHex(v.getBytes(StandardCharsets.UTF_8))));
            return all;
        }

        String json(boolean compact) {
            ObjectNode body = (ObjectNode) Canonicalizer.parseStrict("{}");
            return body.put("name", name).put("phone", compact ? phoneDigits : phoneHyphen())
                    .put("birthDate", compact ? birthCompact() : birth.toString()).toString();
        }
    }

    final Set<String> forbidden = new LinkedHashSet<>();

    Person sent() {
        Person p = Person.random();
        forbidden.addAll(p.forms());
        return p;
    }

    /** 요청 본문에 실제로 넣은 그 밖의 값(틀린 전화·미래 생년월일·모르는 필드 값)도 금지 목록에. */
    String sentValue(String v) {
        forbidden.add(v);
        forbidden.add(HexFormat.of().formatHex(v.getBytes(StandardCharsets.UTF_8)));
        return v;
    }

    ApiTestSupport.Response register(String tenant, String subject, String key, String json) {
        return ApiTestSupport.post(port, "/api/v1/customers", TestJwts.token(tenant, subject), json, Map.of("Idempotency-Key", key));
    }

    List<String> hits(String text) {
        List<String> found = new ArrayList<>();
        int i = 0;
        for (String f : forbidden) {
            if (text.contains(f)) {
                found.add("dynamic-sentinel#" + i);                  // 이름만 — 값은 싣지 않는다
            }
            i++;
        }
        return found;
    }

    static void expire(String tenant, String subject, String key) {
        try (Connection c = DB.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            SeedData.exec(c, "UPDATE idempotency_key SET created_at = now() - interval '2 days', claimed_at = now() - interval '2 days', "
                    + "expires_at = now() - interval '1 minute' WHERE tenant_id = ? AND actor_subject = ? AND idem_key = ?", tenant, subject, key);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void customerRegistrationLeaksTheRequestsOwnValuesNowhere() {
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
        List<String> responses = new CopyOnWriteArrayList<>();
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        ApiTestSupport.observer = (path, r) -> {
            responses.add(r.headers() + "\n" + r.text());
            statuses.add(r.status());
        };
        System.setOut(tee);
        System.setErr(tee);
        try {
            // 성공: 첫 등록 → 재생 → 멱등 만료 뒤 같은 키(NOOP), 하이픈 없는 표기의 등록
            String key = "leak-" + UUID.randomUUID();
            String body = sent().json(false);
            assertThat(register(T, "agent-1", key, body).status()).isEqualTo(201);
            assertThat(register(T, "agent-1", key, body).status()).isEqualTo(201);
            expire(T, "agent-1", key);
            assertThat(register(T, "agent-1", key, body).status()).isEqualTo(201);
            assertThat(register(T, "agent-1", "leak-" + UUID.randomUUID(), sent().json(true)).status()).isEqualTo(201);

            // 형식 400: 틀린 전화·미래 생년월일·모르는 필드(값도 금지 목록)·4 KiB 초과
            Person p = sent();
            List<String> malformed = List.of(
                    "{\"name\":\"" + p.name() + "\",\"phone\":\"" + sentValue("02-" + p.phoneDigits().substring(3, 7) + "-" + p.phoneDigits().substring(7))
                            + "\"}",
                    "{\"name\":\"" + p.name() + "\",\"birthDate\":\"" + sentValue("29" + p.birth().toString().substring(2)) + "\"}",
                    "{\"name\":\"" + p.name() + "\",\"rrn\":\"" + sentValue(p.birthCompact().substring(2) + "-1" + p.phoneDigits().substring(5)) + "\"}",
                    "{\"name\":\"" + p.name().repeat(250) + "\",\"phone\":\"" + p.phoneHyphen() + "\"}",
                    "{\"name\":{\"value\":\"" + p.name() + "\"},\"phone\":\"" + p.phoneHyphen() + "\"}");
            for (String m : malformed) {
                assertThat(register(T, "agent-1", "leak-" + UUID.randomUUID(), m).status()).isEqualTo(400);
            }
            // 키 재사용 422(다른 사람의 본문), 권한 없음 404(관리자), 한도 429(한도 1인 테넌트의 두 번째)
            assertThat(register(T, "agent-1", key, sent().json(false)).status()).isEqualTo(422);
            assertThat(register(T, "manager-1", "leak-" + UUID.randomUUID(), sent().json(false)).status()).isEqualTo(404);
            assertThat(register(LIMITED, "agent-1", "leak-" + UUID.randomUUID(), sent().json(false)).status()).isEqualTo(201);
            assertThat(register(LIMITED, "agent-1", "leak-" + UUID.randomUUID(), sent().json(true)).status()).isEqualTo(429);
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
        assertThat(logLines).as("TRACE logging actually captured JDBC activity").anyMatch(l -> l.contains("customer_ref"));
        assertThat(logLines).as("the access log saw the route by template").anyMatch(l -> l.startsWith("ga.access ") && l.contains("POST /api/v1/customers"));
        assertThat(statuses).contains(201, 400, 404, 422, 429);
        // 금지 목록: 7명 × 10표기 + 그 밖의 보낸 값 3 × 2 — 크기만 단언한다(실패 메시지가 값을 싣지 않게)
        assertThat(forbidden.size()).isEqualTo(7 * 10 + 3 * 2);

        assertThat(hits(String.join("\n", logLines))).as("root TRACE log (ga.access included)").isEmpty();
        assertThat(hits(streams.toString(StandardCharsets.UTF_8))).as("stdout/stderr").isEmpty();
        assertThat(hits(String.join("\n", responses))).as("responses").isEmpty();
        String dump = DB.dumpAllData();
        assertThat(dump).as("the dump has this run's registrations, audit and idempotency rows").contains("api:").contains("CUSTOMER_REGISTER")
                .contains("idempotency_key").contains(T);
        assertThat(hits(dump)).as("database dump (audit, idempotency, outbox, jobs, ...)").isEmpty();
        assertThat(hits(DB.serverLogs())).as("database server log").isEmpty();
    }
}
