package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.compliance.rules.RuleActivationJob;
import com.ga.disclosure.compliance.rules.RuleDistributionService;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.idempotency.IdempotencyService;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4(6A 계획 §4.2): 같은 키·같은 요청 → 같은 바이트(부작용 1회), 같은 키·다른 요청 → 422, 키 없음 → 428, 진행 중 → 409·임차 경과 뒤 인수, TTL은 룰 데이터
 * ({@code api.idempotencyTtlHours} — 코드 변경 없이 {@code expires_at}이 바뀐다), 404·400은 저장하지 않고 청구를 해제한다(같은 키로 고친 요청이 처리된다 —
 * 6A 수용심사 §2 ②), 재생 해시가 어긋나면 500(다른 본문 없음),
 * 작업 IDEMPOTENCY_PURGE는 만료 행만 지운다.
 * 요청 해시 식(SHA-256(JCS{method, routeTemplate, pathVariables, body}))은 이 클래스가 따로 계산해 진행 중 행을 심는 것으로 고정한다.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyIT {

    static final String SCHEDULER = "scheduler-1";
    static final String COMPLIANCE = "compliance-1";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    RuleDistributionService distribution;

    @Autowired
    RuleActivationJob activation;

    @Autowired
    IdempotencyService idempotency;

    /** 링크 둘과 GLOBAL 룰이 오늘 ACTIVE인 새 테넌트({@link ApiTestSupport#activateRules}). */
    String tenant(String variantIdOrNull, Consumer<ObjectNode> edit) {
        String t = SeedData.uniqueTenant("IDEM");
        DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.roleLink(c, t, SCHEDULER, "SCHEDULER");
            SeedData.roleLink(c, t, COMPLIANCE, "COMPLIANCE");
        });
        ApiTestSupport.activateRules(distribution, activation, t, variantIdOrNull, edit);
        return t;
    }

    String tenant() {
        return tenant(null, body -> {
        });
    }

    static String key() {
        return "it-" + UUID.randomUUID();
    }

    ApiTestSupport.Response post(String tenant, String subject, String path, String json, String keyOrNull) {
        return ApiTestSupport.post(port, path, TestJwts.token(tenant, subject), json, keyOrNull == null ? Map.of() : Map.of("Idempotency-Key", keyOrNull));
    }

    static String requestHash(String routeTemplate, Map<String, String> pathVariables, String json) {
        ObjectNode input = (ObjectNode) Canonicalizer.parseStrict("{}");
        input.put("method", "POST").put("routeTemplate", routeTemplate);
        ObjectNode vars = input.putObject("pathVariables");
        pathVariables.forEach(vars::put);
        input.set("body", Canonicalizer.parseStrict(json));
        return Sha256.of(Canonicalizer.canonicalize(input));
    }

    static long count(String tenant, String sql) {
        return DB.asApp(tenant, c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    static Map<String, Object> row(String tenant, String subject, String key) {
        return DB.asApp(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT claim_seq, response_status, response_hash, EXTRACT(EPOCH FROM (expires_at - created_at))::bigint AS ttl_seconds
                      FROM idempotency_key WHERE actor_subject = ? AND idem_key = ?
                    """)) {
                ps.setString(1, subject);
                ps.setString(2, key);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("idempotency row exists").isTrue();
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("claimSeq", rs.getInt("claim_seq"));
                    m.put("status", rs.getObject("response_status"));
                    m.put("hash", rs.getString("response_hash"));
                    m.put("ttlSeconds", rs.getLong("ttl_seconds"));
                    return m;
                }
            }
        });
    }

    /** 진행 중 행을 심는다 — 청구 시각은 DB 시계 기준 {@code claimedAgo} 전. */
    static void inProgress(String tenant, String subject, String key, String requestHash, Duration claimedAgo) {
        DB.seed(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO idempotency_key (tenant_id, actor_subject, idem_key, request_hash, claimed_at, created_at, expires_at)
                    SELECT ?, ?, ?, ?, t, t, t + INTERVAL '1 day' FROM (SELECT now() - make_interval(secs => ?) AS t) x
                    """)) {
                ps.setString(1, tenant);
                ps.setString(2, subject);
                ps.setString(3, key);
                ps.setString(4, requestHash);
                ps.setLong(5, claimedAgo.toSeconds());
                ps.executeUpdate();
            }
        });
    }

    @Test
    void sameKeyAndRequestReplaysTheSameBytesWithOneSideEffect() {
        String t = tenant();
        String k = key();
        ApiTestSupport.Response first = post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{\"limit\": 5}", k);
        assertThat(first.status()).isEqualTo(202);
        assertThat(first.headers()).containsKey("location").doesNotContainKey("idempotency-replayed");
        // 공백·키 순서가 달라도 같은 요청(본문은 JCS로 해시)
        ApiTestSupport.Response again = post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{ \"limit\":5 }", k);
        assertThat(again.status()).isEqualTo(202);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(again.headers()).containsEntry("location", first.headers().get("location")).containsEntry("idempotency-replayed", "true");
        assertThat(first.body()).isEqualTo(Canonicalizer.canonicalize(first.text()));

        assertThat(count(t, "SELECT count(*) FROM async_job")).isEqualTo(1);
        assertThat(count(t, "SELECT count(*) FROM audit_log WHERE action = 'JOB_QUEUED'")).isEqualTo(1);
        Map<String, Object> row = row(t, SCHEDULER, k);
        assertThat(row.get("status")).isEqualTo(202);
        assertThat(row.get("hash")).isEqualTo(Sha256.of(first.body()));
        assertThat(count(t, "SELECT count(*) FROM idempotency_key WHERE response_ref::text LIKE '%limit%'"))
                .as("request body is not stored").isZero();
    }

    @Test
    void sameKeyDifferentRequestIs422AndMissingOrMalformedKeysAreRejectedBeforeAnyWrite() {
        String t = tenant();
        String k = key();
        assertThat(post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{\"limit\":5}", k).status()).isEqualTo(202);
        ApiTestSupport.Response otherBody = post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{\"limit\":6}", k);
        ApiTestSupport.Response otherRoute = post(t, SCHEDULER, "/internal/v1/jobs/EXPIRE", "{\"limit\":5}", k);
        assertThat(otherBody.status()).isEqualTo(422);
        assertThat(otherBody.text()).isEqualTo(
                "{\"code\":\"IDEMPOTENCY_KEY_REUSED\",\"details\":{},\"message\":\"The Idempotency-Key was used for a different request.\"}");
        assertThat(otherRoute.fingerprint()).isEqualTo(otherBody.fingerprint());

        ApiTestSupport.Response missing = post(t, SCHEDULER, "/internal/v1/jobs/EXPIRE", "{}", null);
        assertThat(missing.status()).isEqualTo(428);
        assertThat(missing.text()).isEqualTo(
                "{\"code\":\"IDEMPOTENCY_KEY_REQUIRED\",\"details\":{},\"message\":\"An Idempotency-Key header is required.\"}");
        ApiTestSupport.Response malformed = post(t, SCHEDULER, "/internal/v1/jobs/EXPIRE", "{}", "short");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.text()).contains("\"field\":\"Idempotency-Key\"");
        assertThat(post(t, SCHEDULER, "/internal/v1/jobs/EXPIRE", "{not json", key()).text()).contains("\"field\":\"body\"");
        // 라우트가 먼저다: 없는 라우트는 키가 없어도 404
        assertThat(post(t, SCHEDULER, "/internal/v1/no-such-route", "{}", null).status()).isEqualTo(404);
        assertThat(count(t, "SELECT count(*) FROM async_job")).isEqualTo(1);
        assertThat(count(t, "SELECT count(*) FROM idempotency_key")).isEqualTo(1);
    }

    @Test
    void inProgressIs409UntilTheLeaseExpiresThenTheRequestTakesOver() {
        String t = tenant();
        String hash = requestHash("/internal/v1/jobs/{kind}", Map.of("kind", "NOTIFY"), "{\"limit\":5}");
        String fresh = key();
        inProgress(t, SCHEDULER, fresh, hash, Duration.ZERO);
        ApiTestSupport.Response busy = post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{\"limit\":5}", fresh);
        assertThat(busy.status()).isEqualTo(409);
        assertThat(busy.text()).contains("\"code\":\"IDEMPOTENCY_IN_PROGRESS\"");
        assertThat(count(t, "SELECT count(*) FROM async_job")).isZero();

        String stale = key();
        inProgress(t, SCHEDULER, stale, hash, Duration.ofMinutes(10));   // 룰 임차 120초 경과
        ApiTestSupport.Response taken = post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{\"limit\":5}", stale);
        assertThat(taken.status()).isEqualTo(202);
        assertThat(row(t, SCHEDULER, stale)).containsEntry("claimSeq", 2).containsEntry("status", 202);
    }

    @Test
    void ttlComesFromRuleData() {
        String standard = tenant();
        String longer = tenant("IDEM-IT-TTL48", body -> ((ObjectNode) body.get("api")).put("idempotencyTtlHours", 48));
        String k1 = key();
        String k2 = key();
        assertThat(post(standard, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{}", k1).status()).isEqualTo(202);
        assertThat(post(longer, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{}", k2).status()).isEqualTo(202);
        assertThat(row(standard, SCHEDULER, k1)).containsEntry("ttlSeconds", 24L * 3600);
        assertThat(row(longer, SCHEDULER, k2)).containsEntry("ttlSeconds", 48L * 3600);
    }

    static long keyRows(String tenant, String subject, String key) {
        return DB.asApp(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM idempotency_key WHERE actor_subject = ? AND idem_key = ?")) {
                ps.setString(1, subject);
                ps.setString(2, key);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Test
    void notFoundIsNotStoredAndReleasesTheKey() {
        String t = tenant();
        String k = key();
        // 준법은 VERIFY_TENANT만 제출한다 — NOTIFY는 인가 거부(404)
        ApiTestSupport.Response denied = post(t, COMPLIANCE, "/api/v1/jobs/NOTIFY", "{}", k);
        assertThat(denied.status()).isEqualTo(404);
        assertThat(keyRows(t, COMPLIANCE, k)).as("the claim is released, not left in progress").isZero();
        // 같은 키로 다른(허가된) 요청 — 묶여 있지 않으므로 처리된다
        ApiTestSupport.Response allowed = post(t, COMPLIANCE, "/api/v1/jobs/VERIFY_TENANT", "{}", k);
        assertThat(allowed.status()).as(allowed.text()).isEqualTo(202);
        assertThat(row(t, COMPLIANCE, k)).containsEntry("status", 202);
    }

    /** 6A 수용심사 §2 ②: 유스케이스에 닿지 않은 400 뒤 같은 키로 고친 본문이 오면 정상 처리된다(키는 2xx·409·422만 묶는다). */
    @Test
    void aMalformedRequestReleasesTheKeySoTheCorrectedRequestIsProcessed() {
        String t = tenant();
        String k = key();
        ApiTestSupport.Response malformed = post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{\"limit\":\"five\"}", k);
        assertThat(malformed.status()).as(malformed.text()).isEqualTo(400);
        assertThat(malformed.text()).contains("\"code\":\"MALFORMED_REQUEST\"");
        assertThat(keyRows(t, SCHEDULER, k)).as("no claim survives a 400").isZero();
        assertThat(count(t, "SELECT count(*) FROM async_job")).isZero();

        ApiTestSupport.Response corrected = post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{\"limit\":5}", k);
        assertThat(corrected.status()).as(corrected.text()).isEqualTo(202);
        assertThat(corrected.headers()).doesNotContainKey("idempotency-replayed");
        assertThat(row(t, SCHEDULER, k)).containsEntry("claimSeq", 1).containsEntry("status", 202);
        assertThat(count(t, "SELECT count(*) FROM async_job")).isEqualTo(1);
        // 이제 묶였다 — 다른 본문은 422, 같은 본문은 재생
        assertThat(post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{\"limit\":\"five\"}", k).status()).isEqualTo(422);
        assertThat(post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{\"limit\":5}", k).headers()).containsEntry("idempotency-replayed", "true");
    }

    /** 해제는 그 청구 순번만 — 임차를 넘겨 다른 요청이 인수한 행은 늦게 끝난 첫 요청이 지우지 못하고, 완료 행은 지우지 못한다. */
    @Test
    void releaseRemovesOnlyTheReleasingInProgressClaim() {
        String t = tenant();
        Caller caller = Caller.internal(TenantId.of(t), SCHEDULER);
        String k = key();
        inProgress(t, SCHEDULER, k, requestHash("/internal/v1/jobs/{kind}", Map.of("kind", "NOTIFY"), "{\"limit\":5}"), Duration.ZERO);
        assertThat(idempotency.release(caller, k, 2)).as("another claim's release").isFalse();
        assertThat(keyRows(t, SCHEDULER, k)).isEqualTo(1);
        assertThat(idempotency.release(caller, k, 1)).isTrue();
        assertThat(keyRows(t, SCHEDULER, k)).isZero();

        String done = key();
        assertThat(post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{}", done).status()).isEqualTo(202);
        assertThat(idempotency.release(caller, done, 1)).as("a completed key is not released").isFalse();
        assertThat(row(t, SCHEDULER, done)).containsEntry("status", 202);
    }

    @Test
    void aReplayWhoseBytesNoLongerMatchTheStoredHashIs500WithoutAnotherBody() throws Exception {
        String t = tenant();
        String k = key();
        assertThat(post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{}", k).status()).isEqualTo(202);
        // 완료 행은 GD120이 막으므로 트리거를 끈 슈퍼유저 세션으로 튜플을 바꾼다(저장소 손상 모사)
        try (var c = DB.superuserDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            s.executeUpdate("UPDATE idempotency_key SET response_ref = jsonb_set(response_ref, '{body,status}', '\"SUCCEEDED\"') WHERE tenant_id = '"
                    + t + "'");
        }
        ApiTestSupport.Response replay = post(t, SCHEDULER, "/internal/v1/jobs/NOTIFY", "{}", k);
        assertThat(replay.status()).isEqualTo(500);
        assertThat(replay.text()).isEqualTo("{\"code\":\"INTERNAL_ERROR\",\"details\":{},\"message\":\"Internal error.\"}");
        assertThat(replay.headers()).doesNotContainKey("location").doesNotContainKey("idempotency-replayed");
        assertThat(new String(replay.body(), StandardCharsets.UTF_8)).doesNotContain("SUCCEEDED");
    }

    @Test
    void purgeJobDeletesOnlyExpiredKeys() throws Exception {
        String t = tenant();
        String expired = key();
        String live = key();
        String hash = requestHash("/internal/v1/jobs/{kind}", Map.of("kind", "EXPIRE"), "{}");
        inProgress(t, SCHEDULER, expired, hash, Duration.ofDays(2));    // 만료 = 청구 + 1일 → 하루 전 만료
        inProgress(t, SCHEDULER, live, hash, Duration.ofHours(1));
        ApiTestSupport.Response submitted = post(t, SCHEDULER, "/internal/v1/jobs/IDEMPOTENCY_PURGE", "{}", key());
        assertThat(submitted.status()).isEqualTo(202);
        String location = submitted.headers().get("location");
        String status = "";
        for (long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos(); System.nanoTime() < deadline; Thread.sleep(50)) {
            status = Canonicalizer.parseStrict(ApiTestSupport.get(port, location, TestJwts.token(t, SCHEDULER)).text()).get("status").asString();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                break;
            }
        }
        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(count(t, "SELECT count(*) FROM idempotency_key WHERE idem_key = '" + expired + "'")).isZero();
        assertThat(count(t, "SELECT count(*) FROM idempotency_key WHERE idem_key = '" + live + "'")).isOne();
        // 보고서 열람은 준법만(REPORT_VIEW) — 스케줄러는 같은 404
        assertThat(ApiTestSupport.get(port, location + "/report", TestJwts.token(t, SCHEDULER)).status()).isEqualTo(404);
        ApiTestSupport.Response report = ApiTestSupport.get(port, location.replace("/internal/v1/", "/api/v1/") + "/report",
                TestJwts.token(t, COMPLIANCE));
        assertThat(report.status()).isEqualTo(200);
        assertThat(Canonicalizer.parseStrict(report.text()).get("purged").asInt()).isOne();
        assertThat(report.text()).doesNotContain(expired).doesNotContain(SCHEDULER);
    }
}
