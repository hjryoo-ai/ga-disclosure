package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.compliance.rules.RuleActivationJob;
import com.ga.disclosure.compliance.rules.RuleDistributionService;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G6 HTTP(6B 지시문 §5, 계획 §6): {@code POST /internal/v1/gate}는 게이트 서비스 주체만(그 밖은 없는 라우트와 같은 404), 판정마다 감사 1행(식별자는
 * 해시), 응답에 개인정보 없음(계약 검증 — {@link ApiTestSupport}). <b>무효된 확인서가 아직 쥔 활성 연결은 ALLOWED 근거가 아니다</b>(6B 중간 회신 ③).
 * 질의라 멱등 키 없이 받고 재생하지 않는다. 주체별 분당 한도 초과는 429(감사 없음). 상태 × 서명 × 룰의 전수 대조는 {@code GateServiceTest}.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GateApiIT {

    static final String GATE = "gate-client-1";
    static final String SCHEDULER = "scheduler-1";
    static final String COMPLIANCE = "compliance-1";
    static final String MINE = "CR-" + "1".repeat(32);
    static final String OTHER = "CR-" + "2".repeat(32);

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

    String tenant(String variantIdOrNull, java.util.function.Consumer<ObjectNode> edit) {
        String t = SeedData.uniqueTenant("GATE");
        DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.dataKey(c, t, SeedData.SEED_KEY_ID);
            SeedData.customer(c, t, MINE);
            SeedData.customer(c, t, OTHER);
            SeedData.roleLink(c, t, GATE, "GATE_CLIENT");
            SeedData.roleLink(c, t, SCHEDULER, "SCHEDULER");
            SeedData.roleLink(c, t, COMPLIANCE, "COMPLIANCE");
        });
        ApiTestSupport.activateRules(distribution, activation, t, variantIdOrNull, edit);
        return t;
    }

    static UUID disclosure(String t, String status, String customer, char hash) {
        UUID[] id = new UUID[1];
        DB.seed(t, c -> id[0] = SeedData.disclosure(c, t, status, SeedData.hash(hash), "DISC-2026-07", customer));
        return id[0];
    }

    /** 청약번호는 작성 때만 쓰인다(GD132) — 봉인 이후 픽스처에는 트리거를 끄고 넣는다. */
    static void applicationNo(String t, UUID id, String no) {
        try (Connection c = DB.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            SeedData.exec(c, "UPDATE disclosure SET application_no = ? WHERE tenant_id = ? AND disclosure_id = ?", no, t, id);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    String number(String t, UUID id) {
        return DB.asApp(t, c -> SeedData.call(c, "SELECT disclosure_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", t, id));
    }

    ApiTestSupport.Response gate(String t, String subject, String json) {
        return ApiTestSupport.post(port, "/internal/v1/gate", TestJwts.token(t, subject), json, Map.of());
    }

    static String byApplication(String no, String customer) {
        return "{\"applicationNo\":\"" + no + "\",\"customerRef\":\"" + customer + "\"}";
    }

    static String byPolicy(String no, String customer) {
        return "{\"policyNo\":\"" + no + "\",\"customerRef\":\"" + customer + "\"}";
    }

    JsonNode decided(ApiTestSupport.Response r) {
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return Canonicalizer.parseStrict(r.text());
    }

    long gateAudits(String t) {
        return Long.parseLong(DB.asApp(t, c -> SeedData.call(c, "SELECT count(*)::text FROM audit_log WHERE tenant_id = ? AND action = 'GATE_DECISION'", t)));
    }

    @Test
    void decisionsFollowTheCandidateRuleAndAVoidedHolderIsNeverEvidence() {
        String t = tenant(null, body -> {
        });
        UUID completed = disclosure(t, "COMPLETED", MINE, 'a');
        applicationNo(t, completed, "APP-G1");
        UUID sealed = disclosure(t, "SEALED", MINE, 'b');
        applicationNo(t, sealed, "APP-G2");
        UUID twinA = disclosure(t, "SEALED", MINE, 'c');
        applicationNo(t, twinA, "APP-G6");
        UUID twinB = disclosure(t, "COMPLETED", MINE, 'd');
        applicationNo(t, twinB, "APP-G6");
        // 증권번호: 완료된 확인서가 쥔 연결은 근거, 무효된 확인서가 아직 쥔 활성 연결은 근거가 아니다(같은 고객이어도)
        // 무효 뒤 다시 쓴 확인서: 같은 청약번호의 무효본은 후보가 아니다 — 모호가 아니라 새 확인서로 판정
        UUID voidedFirst = disclosure(t, "VOID", MINE, '7');
        applicationNo(t, voidedFirst, "APP-G7");
        UUID reissued = disclosure(t, "COMPLETED", MINE, '8');
        applicationNo(t, reissued, "APP-G7");
        UUID linked = disclosure(t, "COMPLETED", MINE, 'e');
        DB.seed(t, c -> SeedData.contractLink(c, t, linked, "POL-G4", "2026-09-30"));
        UUID voided = disclosure(t, "VOID", MINE, 'f');
        DB.seed(t, c -> SeedData.contractLink(c, t, voided, "POL-G5", "2026-09-30"));
        assertThat(DB.<String>asApp(t, c -> SeedData.call(c, """
                SELECT d.status FROM contract_link l JOIN disclosure d ON d.tenant_id = l.tenant_id AND d.disclosure_id = l.disclosure_id
                 WHERE l.tenant_id = ? AND l.policy_no = 'POL-G5' AND l.superseded_by IS NULL AND l.carried_to IS NULL""", t)))
                .as("the voided disclosure still holds the active link").isEqualTo("VOID");

        JsonNode allowed = decided(gate(t, GATE, byApplication("APP-G1", MINE)));
        assertThat(allowed.get("decision").asString()).isEqualTo("ALLOWED");
        assertThat(allowed.get("reason").asString()).isEqualTo("SATISFIED");
        assertThat(allowed.get("disclosureNo").asString()).isEqualTo(number(t, completed));
        assertThat(allowed.get("ruleVersionId").asString()).isEqualTo("DISC-2026-07");

        JsonNode pending = decided(gate(t, GATE, byApplication("APP-G2", MINE)));
        assertThat(pending.get("decision").asString()).isEqualTo("BLOCKED");
        assertThat(pending.get("reason").asString()).isEqualTo("PENDING");
        assertThat(pending.get("pendingRoles")).extracting(JsonNode::asString).containsExactly("CUSTOMER", "AGENT", "MANAGER");

        assertThat(decided(gate(t, GATE, byPolicy("POL-G4", MINE))).get("decision").asString()).isEqualTo("ALLOWED");
        JsonNode voidedLink = decided(gate(t, GATE, byPolicy("POL-G5", MINE)));
        assertThat(voidedLink.get("decision").asString()).isEqualTo("BLOCKED");
        assertThat(voidedLink.get("reason").asString()).isEqualTo("NO_DISCLOSURE");
        assertThat(voidedLink.get("disclosureNo").isNull()).isTrue();

        JsonNode mismatch = decided(gate(t, GATE, byApplication("APP-G1", OTHER)));
        assertThat(mismatch.get("reason").asString()).isEqualTo("CUSTOMER_MISMATCH");
        assertThat(mismatch.get("disclosureNo").isNull()).isTrue();
        assertThat(mismatch.get("ruleVersionId").isNull()).isTrue();
        assertThat(decided(gate(t, GATE, byApplication("APP-G6", MINE))).get("reason").asString()).isEqualTo("AMBIGUOUS");
        assertThat(decided(gate(t, GATE, byPolicy("POL-NONE", MINE))).get("reason").asString()).isEqualTo("NO_DISCLOSURE");
        JsonNode reissuedDecision = decided(gate(t, GATE, byApplication("APP-G7", MINE)));
        assertThat(reissuedDecision.get("decision").asString()).isEqualTo("ALLOWED");
        assertThat(reissuedDecision.get("disclosureNo").asString()).isEqualTo(number(t, reissued));
        long decisions = 8;

        // 응답·감사에 식별자 원문·고객 가명이 없다
        String audits = DB.asApp(t, c -> SeedData.call(c, "SELECT string_agg(detail::text, '') FROM audit_log WHERE tenant_id = ? AND action = 'GATE_DECISION'", t));
        assertThat(audits).doesNotContain("APP-G").doesNotContain("POL-G").doesNotContain(MINE).doesNotContain(OTHER).contains("identifierSha256");
        assertThat(gateAudits(t)).isEqualTo(decisions);

        // 다른 주체는 없는 라우트와 같은 404, 판정 감사 없음
        ApiTestSupport.Response noRoute = gate(t, SCHEDULER, null);
        ApiTestSupport.Response unrouted = ApiTestSupport.post(port, "/internal/v1/no-such-route", TestJwts.token(t, SCHEDULER), "{}", Map.of());
        for (String subject : new String[] {SCHEDULER, COMPLIANCE}) {
            ApiTestSupport.Response denied = gate(t, subject, byApplication("APP-G1", MINE));
            assertThat(denied.status()).as(subject).isEqualTo(404);
            assertThat(denied.fingerprint()).as(subject).isEqualTo(unrouted.fingerprint());
        }
        assertThat(noRoute.status()).isEqualTo(404);
        // 형식 오류는 400(값 없음), 판정 감사 없음
        for (String bad : new String[] {"{\"applicationNo\":\"APP-G1\",\"policyNo\":\"POL-G4\",\"customerRef\":\"" + MINE + "\"}",
                "{\"customerRef\":\"" + MINE + "\"}", byApplication("APP-G1", "C-0001"), byApplication("HAS SPACE", MINE)}) {
            ApiTestSupport.Response malformed = gate(t, GATE, bad);
            assertThat(malformed.status()).as(bad).isEqualTo(400);
            assertThat(malformed.text()).doesNotContain("APP-G1").doesNotContain("HAS SPACE");
        }
        assertThat(gateAudits(t)).isEqualTo(decisions);

        // 질의라 멱등 키를 받지 않고 재생하지 않는다 — 같은 키로 두 번 물어도 두 번 판정·감사
        ApiTestSupport.Response first = ApiTestSupport.post(port, "/internal/v1/gate", TestJwts.token(t, GATE), byApplication("APP-G2", MINE),
                Map.of("Idempotency-Key", "gate-same-key"));
        ApiTestSupport.Response again = ApiTestSupport.post(port, "/internal/v1/gate", TestJwts.token(t, GATE), byApplication("APP-G2", MINE),
                Map.of("Idempotency-Key", "gate-same-key"));
        assertThat(first.status()).isEqualTo(200);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.headers()).doesNotContainKey("idempotency-replayed").doesNotContainKey("Idempotency-Replayed");
        assertThat(gateAudits(t)).isEqualTo(decisions + 2);
        assertThat(DB.<String>asApp(t, c -> SeedData.call(c, "SELECT count(*)::text FROM idempotency_key WHERE tenant_id = ?", t))).isEqualTo("0");
    }

    @Test
    void thePerMinuteLimitAnswers429WithoutAnAudit() {
        String t = tenant("DISC-GATE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                body -> ((ObjectNode) body.get("gate")).put("perMinutePerPrincipal", 3));
        int ok = 0;
        boolean limited = false;
        for (int i = 0; i < 7 && !limited; i++) {                       // 분 경계를 한 번 넘어도 한도 + 1번째 안에 429가 온다
            ApiTestSupport.Response r = gate(t, GATE, byPolicy("POL-NONE", MINE));
            if (r.status() == 429) {
                limited = true;
                assertThat(Canonicalizer.parseStrict(r.text()).get("code").asString()).isEqualTo("RATE_LIMITED");
            } else {
                assertThat(r.status()).isEqualTo(200);
                ok++;
            }
        }
        assertThat(limited).isTrue();
        assertThat(ok).isGreaterThanOrEqualTo(3);
        assertThat(gateAudits(t)).isEqualTo(ok);
    }

    /**
     * 6B 이월 ②(Phase 8): 한도는 DB 집계라 인스턴스가 둘이어도 하나다 — 같은 DB를 쓰는 두 번째 앱에 번갈아 보내도 한도 3이면 정확히 3번 200, 그 뒤 429
     * (6B의 인스턴스 메모리 창이면 인스턴스마다 3번씩 6번이 통과했다). 창은 지난 60초라 분 경계와 무관하다.
     */
    @Test
    void theLimitIsOneLimitAcrossTwoInstances() {
        String t = tenant("DISC-GATE2-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                body -> ((ObjectNode) body.get("gate")).put("perMinutePerPrincipal", 3));
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of("--server.port=0"));
        ApiTestSupport.propertyMap().forEach((k, v) -> args.add("--" + k + "=" + v.get()));
        try (var second = new org.springframework.boot.builder.SpringApplicationBuilder(DisclosureApplication.class)
                .web(org.springframework.boot.WebApplicationType.SERVLET).run(args.toArray(String[]::new))) {
            int secondPort = Integer.parseInt(second.getEnvironment().getRequiredProperty("local.server.port"));
            java.util.List<Integer> statuses = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int target = i % 2 == 0 ? port : secondPort;
                statuses.add(ApiTestSupport.post(target, "/internal/v1/gate", TestJwts.token(t, GATE), byPolicy("POL-NONE", MINE), Map.of()).status());
            }
            assertThat(statuses).containsExactly(200, 200, 200, 429, 429, 429);
            assertThat(gateAudits(t)).isEqualTo(3);
        }
    }
}
