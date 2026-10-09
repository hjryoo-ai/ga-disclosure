package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6B §9 고객 등록 API — 승인 §4 조건 1·2·3·6(10단계 회신으로 다시 쓴 조건 3)을 HTTP로:
 * <ul>
 *   <li>조건 2: 응답은 {@code {customerRef, receiptId}}뿐, {@code Location} 없음.</li>
 *   <li>조건 3: 응답은 성공(첫 등록·재생·멱등 만료 뒤 NOOP — 같은 바이트) · 형식 400 · 한도 429 · 키 재사용 422뿐이고 다른 고객의 존재를 반영하지
 *       않는다. 같은 사람을 다른 키로 두 번 등록하면 가명이 둘이다. 한도는 룰 데이터(주체별, DB 집계 — 동시 요청에서도 정확히 한도만큼).</li>
 *   <li>조건 1: 멱등 행의 요청 해시는 HMAC(키 없는 SHA-256이 아니다), 영수증 튜플은 가명·영수증뿐.</li>
 *   <li>조건 6: HTTP 등록은 Phase 2 등록 그대로 — 등록 키 {@code api:…}, 암호화 컬럼, 감사 detail 키 집합, 영수증은 결정론적 파생.</li>
 * </ul>
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerRegisterIT {

    static final String T = SeedData.uniqueTenant("CREG");
    static final String LIMITED = SeedData.uniqueTenant("CLIM");
    static final String BODY = "{\"name\":\"가상등록고객\",\"phone\":\"010-0000-1234\",\"birthDate\":\"1900-02-03\"}";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void prepare() {
        FlowSupport.prepare(T);
        FlowSupport.prepare(LIMITED, FlowSupport.variantBundle("DISC-CUSTLIM-3", b -> ((ObjectNode) b.get("customers")).put("registerPerMinute", 3)));
        for (String t : List.of(T, LIMITED)) {
            DB.seed(t, c -> {
                SeedData.identityLink(c, t, "agent-2", "DEMO-AGENT-2", "AGENT");
                SeedData.roleLink(c, t, "scheduler-1", "SCHEDULER");
            });
        }
    }

    @Value("${local.server.port}")
    int port;

    ApiTestSupport.Response register(String tenant, String subject, String key, String json) {
        return ApiTestSupport.post(port, "/api/v1/customers", TestJwts.token(tenant, subject), json, Map.of("Idempotency-Key", key));
    }

    static String key() {
        return "reg-" + UUID.randomUUID();
    }

    static String text(String tenant, String sql, Object... params) {
        return DB.asApp(tenant, c -> SeedData.call(c, sql, params));
    }

    static long count(String tenant, String sql, Object... params) {
        return Long.parseLong(text(tenant, sql, params));
    }

    static long customers(String tenant) {
        return count(tenant, "SELECT count(*)::text FROM customer_ref WHERE tenant_id = ?", tenant);
    }

    static long registerAudits(String tenant) {
        return count(tenant, "SELECT count(*)::text FROM audit_log WHERE tenant_id = ? AND action = 'CUSTOMER_REGISTER'", tenant);
    }

    /** 멱등 기록이 만료된 것처럼 만든다(시험 전용 — 트리거를 끈 superuser 연결). */
    static void expire(String tenant, String subject, String key) {
        try (Connection c = DB.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            SeedData.exec(c, "UPDATE idempotency_key SET created_at = now() - interval '2 days', claimed_at = now() - interval '2 days', "
                    + "expires_at = now() - interval '1 minute' WHERE tenant_id = ? AND actor_subject = ? AND idem_key = ?",
                    tenant, subject, key);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static String keyed(java.nio.file.Path keyFile, byte[]... parts) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(java.util.Base64.getDecoder().decode(java.nio.file.Files.readString(keyFile).strip()),
                    "HmacSHA256"));
            for (byte[] p : parts) {
                mac.update(p);
            }
            return java.util.HexFormat.of().formatHex(mac.doFinal());
        } catch (java.io.IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 영수증 = HMAC(영수증 키, 라벨 ‖ 0 ‖ 테넌트 ‖ 0 ‖ 가명 ‖ 0 ‖ 등록 키)[0..16] → UUIDv8. */
    static String expectedReceipt(String tenant, String ref, String registrationKey) {
        byte[] mac = java.util.HexFormat.of().parseHex(keyed(ApiTestSupport.RECEIPT_KEY, "ga-customer-receipt/v1".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0}, tenant.getBytes(StandardCharsets.UTF_8), new byte[] {0}, ref.getBytes(StandardCharsets.UTF_8), new byte[] {0},
                registrationKey.getBytes(StandardCharsets.UTF_8)));
        mac[6] = (byte) ((mac[6] & 0x0f) | 0x80);
        mac[8] = (byte) ((mac[8] & 0x3f) | 0x80);
        ByteBuffer b = ByteBuffer.wrap(mac, 0, 16);
        return new UUID(b.getLong(), b.getLong()).toString();
    }

    static String registrationKey(String subject, String idempotencyKey) {
        byte[] s = subject.getBytes(StandardCharsets.UTF_8);
        byte[] k = idempotencyKey.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[s.length + 1 + k.length];
        System.arraycopy(s, 0, all, 0, s.length);
        System.arraycopy(k, 0, all, s.length + 1, k.length);
        return "api:" + Sha256.of(all).substring(0, 40);
    }

    @Test
    void theResponseIsThePseudonymAndReceiptOnlyAndANoopAfterExpiryIsTheSameBytes() {
        String key = key();
        long before = customers(T);
        ApiTestSupport.Response first = register(T, "agent-1", key, BODY);
        assertThat(first.status()).as(first.text()).isEqualTo(201);
        JsonNode receipt = Canonicalizer.parseStrict(first.text());
        // 조건 2: 가명과 영수증뿐(생성 여부·Location 없음)
        assertThat(receipt.propertyNames()).containsExactlyInAnyOrder("customerRef", "receiptId");
        assertThat(first.headers()).doesNotContainKey("location").doesNotContainKey("idempotency-replayed");
        assertThat(customers(T)).isEqualTo(before + 1);

        // 재생: 같은 본문 + 재생 표시
        ApiTestSupport.Response replay = register(T, "agent-1", key, BODY);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.text()).isEqualTo(first.text());
        assertThat(replay.headers()).containsEntry("idempotency-replayed", "true");

        // 멱등 기록 만료 뒤 같은 키: 유스케이스까지 가서 Phase 2 NOOP — 첫 응답과 같은 바이트(상태·헤더−Date·본문)
        expire(T, "agent-1", key);
        ApiTestSupport.Response noop = register(T, "agent-1", key, BODY);
        assertThat(noop.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(customers(T)).isEqualTo(before + 1);
        String regKey = registrationKey("agent-1", key);
        assertThat(text(T, """
                SELECT string_agg(detail ->> 'outcome', ',' ORDER BY seq) FROM audit_log
                 WHERE tenant_id = ? AND action = 'CUSTOMER_REGISTER' AND detail ->> 'registrationKey' = ?""", T, regKey))
                .as("Phase 2 audit rows as they are").isEqualTo("CREATED,NOOP");

        // 같은 사람(같은 이름·전화·생년월일)을 다른 키로: 201 둘, 가명 둘 — "이미 등록됨" 신호가 없다
        ApiTestSupport.Response again = register(T, "agent-1", key(), BODY);
        assertThat(again.status()).isEqualTo(201);
        JsonNode second = Canonicalizer.parseStrict(again.text());
        assertThat(second.get("customerRef").asString()).isNotEqualTo(receipt.get("customerRef").asString());
        assertThat(second.get("receiptId").asString()).isNotEqualTo(receipt.get("receiptId").asString());
        assertThat(customers(T)).isEqualTo(before + 2);
        // 다른 주체가 같은 멱등 키를 써도 다른 등록 키 — 다른 고객
        ApiTestSupport.Response other = register(T, "agent-2", key, BODY);
        assertThat(other.status()).isEqualTo(201);
        assertThat(Canonicalizer.parseStrict(other.text()).get("customerRef").asString()).isNotEqualTo(receipt.get("customerRef").asString());

        // 같은 키·다른 본문: 공통 멱등 규칙 422(그 주체 자기 키의 사실 — 고객 존재와 무관)
        ApiTestSupport.Response reused = register(T, "agent-1", key, "{\"name\":\"다른가상고객\"}");
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.text()).contains("\"code\":\"IDEMPOTENCY_KEY_REUSED\"");
        assertThat(customers(T)).isEqualTo(before + 3);
    }

    /** 가명·영수증 값만 자리표시로 바꾼 지문 — 나머지(상태·헤더−Date·본문 모양·길이)는 그대로. */
    static String shape(ApiTestSupport.Response r) {
        return r.fingerprint().replaceAll("CR-[0-9a-f]{32}", "CR-*").replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "UUID");
    }

    @Test
    void noResponseReflectsWhetherTheSamePersonAlreadyExists() {
        String fresh = "{\"name\":\"가상처음고객\",\"phone\":\"010-0000-5678\",\"birthDate\":\"1900-04-05\"}";
        ApiTestSupport.Response firstTime = register(T, "agent-1", key(), fresh);
        // 같은 사람이 이미 있다(같은 설계사·다른 설계사가 등록) — 응답은 처음과 같은 바이트(값 자리만 다르다)
        ApiTestSupport.Response secondTime = register(T, "agent-1", key(), fresh);
        ApiTestSupport.Response byOther = register(T, "agent-2", key(), fresh);
        ApiTestSupport.Response someoneElse = register(T, "agent-1", key(), BODY.replace("가상등록고객", "가상다른고객"));
        assertThat(firstTime.status()).isEqualTo(201);
        assertThat(List.of(secondTime, byOther, someoneElse)).allSatisfy(r -> assertThat(shape(r)).isEqualTo(shape(firstTime)));
    }

    @Test
    void theHttpRegistrationIsThePhase2RegistrationAndTheReceiptIsDerived() {
        String key = key();
        ApiTestSupport.Response r = register(T, "agent-1", key, "{\"name\":\"가상등록고객이\"}");
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        JsonNode receipt = Canonicalizer.parseStrict(r.text());
        String ref = receipt.get("customerRef").asString();
        String regKey = registrationKey("agent-1", key);
        // 조건 6: 등록 키·암호화 컬럼(전화·생년월일 없음은 NULL)·감사 detail 키 집합이 Phase 2 그대로
        assertThat(text(T, """
                SELECT registration_key || '|' || (name_enc IS NOT NULL) || '|' || (phone_enc IS NULL) || '|' || (birth_date_enc IS NULL) || '|' || (enc_key_id IS NOT NULL)
                  FROM customer_ref WHERE tenant_id = ? AND customer_ref = ?""", T, ref)).isEqualTo(regKey + "|true|true|true|true");
        assertThat(text(T, """
                SELECT string_agg(k, ',' ORDER BY k) FROM audit_log, jsonb_object_keys(detail) k
                 WHERE tenant_id = ? AND action = 'CUSTOMER_REGISTER' AND target_id = ?""", T, ref))
                .isEqualTo("hasBirthDate,hasPhone,keyId,outcome,registrationKey");
        // 영수증은 서버 키 HMAC(테넌트 ‖ 가명 ‖ 등록 키)의 결정론적 파생
        assertThat(receipt.get("receiptId").asString()).isEqualTo(expectedReceipt(T, ref, regKey));
        // 조건 1: 멱등 행 — 요청 해시는 HMAC(키 없는 SHA-256이 아니다), 영수증 튜플은 가명·영수증뿐
        ObjectNode input = (ObjectNode) Canonicalizer.parseStrict("{}");
        input.put("method", "POST").put("routeTemplate", "/api/v1/customers");
        input.putObject("pathVariables");
        input.set("body", Canonicalizer.parseStrict("{\"name\":\"가상등록고객이\"}"));
        byte[] canonical = Canonicalizer.canonicalize(input);
        String stored = text(T, "SELECT request_hash || '|' || response_ref::text FROM idempotency_key WHERE tenant_id = ? AND idem_key = ?", T, key);
        assertThat(stored.substring(0, 64)).isEqualTo(keyed(ApiTestSupport.REQUEST_HASH_KEY, canonical)).isNotEqualTo(Sha256.of(canonical));
        assertThat(Canonicalizer.parseStrict(stored.substring(65)).propertyNames()).as("receipt tuple keys").containsExactly("body");
        assertThat(Canonicalizer.parseStrict(stored.substring(65)).at("/body").propertyNames()).containsExactlyInAnyOrder("customerRef", "receiptId");
        assertThat(stored.contains("가상등록고객이")).as("the stored row carries no request value").isFalse();
    }

    @Test
    void malformedInputIsA400WithTheFieldNameOnly() {
        long before = customers(T);
        long audits = registerAudits(T);
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("{\"phone\":\"010-0000-1234\"}", "name");
        cases.put("{\"name\":\"   \"}", "name");
        cases.put("{\"name\":\"가상\\u0007고객\"}", "name");
        cases.put("{\"name\":\"가상고객\",\"phone\":\"02-1234-5678\"}", "phone");
        cases.put("{\"name\":\"가상고객\",\"birthDate\":\"2999-01-01\"}", "birthDate");
        cases.put("{\"name\":\"가상고객\",\"birthDate\":\"1985-02-30\"}", "birthDate");
        cases.put("{\"name\":\"가상고객\",\"rrn\":\"000000-0000000\"}", "rrn");                 // 모르는 필드(주민번호 필드는 스키마에 없다) — 필드 이름만
        // 문자열 자리에 객체·배열(스칼라 숫자는 앱 전체 Jackson 규약대로 문자열로 바뀐 뒤 값객체가 검사한다 — 관찰, 보고서)
        cases.put("{\"name\":\"가상고객\",\"birthDate\":{\"y\":1985}}", null);
        cases.put("{\"name\":[\"가상고객\"]}", null);
        cases.put("{\"name\":\"" + "가".repeat(1500) + "\"}", "body");                        // 4 KiB 상한(UTF-8 3바이트 × 1500)
        cases.put("not json", null);
        // 실패 메시지는 사례 번호만 싣는다(요청 본문·응답을 시험 출력에 옮기지 않는다)
        int i = 0;
        for (Map.Entry<String, String> c : cases.entrySet()) {
            String label = "case " + i++;
            ApiTestSupport.Response r = register(T, "agent-1", key(), c.getKey());
            assertThat(r.status()).as(label).isEqualTo(400);
            JsonNode problem = Canonicalizer.parseStrict(r.text());
            assertThat(problem.get("code").asString()).as(label).isEqualTo("MALFORMED_REQUEST");
            assertThat(problem.get("details").propertyNames()).as(label).isSubsetOf("field");
            if (c.getValue() != null) {
                assertThat(problem.at("/details/field").asString()).as(label).isEqualTo(c.getValue());
            }
            assertThat(List.of("02-1234-5678", "2999-01-01", "1985-02-30", "000000-0000000", "가상").stream().anyMatch(r.text()::contains))
                    .as(label + ": the response carries none of the sent values").isFalse();
        }
        assertThat(customers(T)).isEqualTo(before);
        assertThat(registerAudits(T)).as("malformed requests are not registration attempts").isEqualTo(audits);
    }

    /** §9 승인 R1: 모르는 필드는 앱 전체에서 400(`field` = 그 이름) — 고객 등록만의 특례가 아니다. */
    @Test
    void unknownFieldsAreRejectedOnEveryRoute() {
        ApiTestSupport.Response draft = ApiTestSupport.post(port, "/api/v1/disclosures", TestJwts.token(T, "agent-1"),
                "{\"customerRef\":\"CR-0000000000000000000000000000000a\",\"groupCode\":\"PG-HEALTH-SIMPLE-NR\",\"consultDate\":\"2026-09-25\","
                        + "\"templateType\":\"STANDARD\",\"discount\":10}", Map.of("Idempotency-Key", key()));
        assertThat(draft.status()).isEqualTo(400);
        assertThat(draft.text()).isEqualTo("{\"code\":\"MALFORMED_REQUEST\",\"details\":{\"field\":\"discount\"},\"message\":\"The request is malformed.\"}");
        ApiTestSupport.Response hold = ApiTestSupport.post(port, "/api/v1/legal-holds", TestJwts.token(T, "compliance-1"),
                "{\"customerRef\":\"CR-0000000000000000000000000000000a\",\"reasonCode\":\"LITIGATION\",\"note\":\"x\"}", Map.of("Idempotency-Key", key()));
        assertThat(hold.status()).isEqualTo(400);
        assertThat(Canonicalizer.parseStrict(hold.text()).at("/details/field").asString()).isEqualTo("note");
    }

    @Test
    void onlyAgentsHaveTheCellEveryoneElseGetsTheSame404() {
        ApiTestSupport.Response noRoute = ApiTestSupport.get(port, "/api/v1/no-such-route", TestJwts.token(T, "manager-1"));
        for (String subject : List.of("manager-1", "compliance-1")) {
            // 본문이 틀려도 404 — 형식 검사는 인가 뒤(400이 경로 존재를 알리지 않는다)
            for (String body : List.of(BODY, "{\"phone\":\"bad\"}")) {
                assertThat(register(T, subject, key(), body).fingerprint()).as(subject + " " + body).isEqualTo(noRoute.fingerprint());
            }
        }
        ApiTestSupport.Response internalNoRoute = ApiTestSupport.get(port, "/internal/v1/no-such-route", TestJwts.token(T, "scheduler-1"));
        assertThat(ApiTestSupport.post(port, "/internal/v1/customers", TestJwts.token(T, "scheduler-1"), BODY, Map.of("Idempotency-Key", key()))
                .fingerprint()).isEqualTo(internalNoRoute.fingerprint());
        assertThat(register(T, "scheduler-1", key(), BODY).fingerprint()).isEqualTo(noRoute.fingerprint());
    }

    @Test
    void theLimitIsRuleDataPerSubjectA429ThatReleasesTheKeyAndHoldsUnderConcurrency() throws Exception {
        // 동시 8건, 한도 3(룰 DISC-CUSTLIM-3) — 주체별 잠금 아래 DB 집계라 정확히 3건만 통과
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<String> keys = new ArrayList<>();
        List<Future<ApiTestSupport.Response>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                String k = key();
                keys.add(k);
                futures.add(pool.submit((Callable<ApiTestSupport.Response>) () -> register(LIMITED, "agent-1", k, BODY)));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<ApiTestSupport.Response> f : futures) {
                statuses.add(f.get().status());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(3);
            assertThat(statuses).filteredOn(s -> s == 429).hasSize(5);
        } finally {
            pool.shutdownNow();
        }
        ApiTestSupport.Response limited = register(LIMITED, "agent-1", key(), BODY);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.text()).isEqualTo("{\"code\":\"RATE_LIMITED\",\"details\":{},\"message\":\"Too many requests; retry later.\"}");
        assertThat(customers(LIMITED)).isEqualTo(3 + 3);                 // 데모 고객 3 + 등록 3
        assertThat(registerAudits(LIMITED)).as("a 429 writes nothing").isEqualTo(3 + 3);
        // 429는 멱등 키를 묶지 않는다(완료로 저장하지 않고 해제) — 그 키의 행이 없다
        assertThat(count(LIMITED, "SELECT count(*)::text FROM idempotency_key WHERE tenant_id = ? AND actor_subject = 'agent-1' AND response_status = 429",
                LIMITED)).isZero();
        assertThat(count(LIMITED, "SELECT count(*)::text FROM idempotency_key WHERE tenant_id = ? AND actor_subject = 'agent-1'", LIMITED)).isEqualTo(3);
        // 주체별: 다른 설계사는 영향이 없다
        assertThat(register(LIMITED, "agent-2", key(), BODY).status()).isEqualTo(201);
    }
}
