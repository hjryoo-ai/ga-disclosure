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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G9(Phase 8, 승인 Q8): {@code GET /api/v1/rules/vocabulary} — 설계사·관리자·준법 같은 응답, 서비스 주체는 없는 라우트와 같은 404. 내용은 오늘 시행 중인
 * 룰(여기서는 DISC-2026-07 하나)의 닫힌 목록과 같다(기대값은 번들 파일에서 읽는다 — 시험에 코드 상수 없음): 추천사유는 {@code auto}를 뺀 룰 순서,
 * 사유·해소 코드는 코드·표기만, 채널은 켜진 것만, 서명자는 룰 순서. 응답의 모든 잎은 문자열이다(수·불리언 0 — 임계치·한도·가시성·텍스트 필수 여부가
 * 없다). 응답은 계약을 지난다({@link ApiTestSupport}가 매 응답을 계약으로 검증). 서버 거부는 그대로 — 목록의 코드는 받고 목록 밖 코드는 지금처럼 거부한다.
 * 룰 없는 테넌트는 쓰기와 같은 503.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuleVocabularyApiIT {

    static final String T = SeedData.uniqueTenant("VOCAB");
    static final String PATH = "/api/v1/rules/vocabulary";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void prepare() {
        FlowSupport.prepare(T);
        DB.seed(T, c -> SeedData.roleLink(c, T, "scheduler-1", "SCHEDULER"));
    }

    @Value("${local.server.port}")
    int port;

    static JsonNode rule() {
        try {
            return Canonicalizer.parseStrict(Files.readString(Path.of(System.getProperty("ga.repoRoot"), "contracts/rules/bundles/rules/DISC-2026-07.bundle.json")))
                    .get("body");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 룰 목록의 {code, label}만(다른 키는 버린다), 순서 그대로. {@code auto} 항목은 뺀다. */
    static String codeLabels(JsonNode list) {
        StringBuilder sb = new StringBuilder("[");
        for (JsonNode n : list) {
            if (n.path("auto").asBoolean(false)) {
                continue;
            }
            sb.append(sb.length() == 1 ? "" : ",").append("{\"code\":").append(n.get("code")).append(",\"label\":").append(n.get("label")).append('}');
        }
        return sb.append(']').toString();
    }

    static void leavesAreStrings(String at, JsonNode n, List<String> out) {
        if (n.isObject()) {
            n.properties().forEach(e -> leavesAreStrings(at + "/" + e.getKey(), e.getValue(), out));
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                leavesAreStrings(at + "/" + i, n.get(i), out);
            }
        } else if (!n.isString()) {
            out.add(at + " is " + n.getNodeType());
        }
    }

    @Test
    void humanRolesReadTheClosedListsOfTodaysRuleAndNothingElse() {
        JsonNode rule = rule();
        String first = null;
        String etag = null;
        for (String subject : List.of("agent-1", "manager-1", "compliance-1")) {
            ApiTestSupport.Response r = ApiTestSupport.get(port, PATH, TestJwts.token(T, subject));
            assertThat(r.status()).as(subject).isEqualTo(200);
            assertThat(r.headers().get("etag")).as(subject).matches("\"[0-9a-f]{32}\"");
            if (first == null) {
                first = r.text();
                etag = r.headers().get("etag");
            }
            assertThat(r.text()).as("same vocabulary for every human role").isEqualTo(first);
            assertThat(r.headers().get("etag")).as("same day, same rule").isEqualTo(etag);
        }
        JsonNode v = Canonicalizer.parseStrict(first);
        assertThat(v.propertyNames()).containsExactlyInAnyOrder("asOf", "reasonCodes", "voidReasons", "supersedeReasons", "legalHoldReasons",
                "legalHoldReleaseReasons", "draftAbandonReasons", "flagTypes", "channels", "signerRoles");
        List<String> notStrings = new ArrayList<>();
        leavesAreStrings("", v, notStrings);
        assertThat(notStrings).as("no number or boolean anywhere").isEmpty();

        assertThat(v.get("reasonCodes").toString()).isEqualTo(codeLabels(rule.get("reasonCodes")));
        assertThat(v.get("reasonCodes").toString()).as("the system-added code is not a choice").doesNotContain("CUSTOMER_REQUEST");
        assertThat(v.get("voidReasons").toString()).isEqualTo(codeLabels(rule.get("voidReasons")));
        assertThat(v.get("supersedeReasons").toString()).isEqualTo(codeLabels(rule.get("supersedeReasons")));
        assertThat(v.get("legalHoldReasons").toString()).isEqualTo(codeLabels(rule.get("legalHoldReasons")));
        assertThat(v.get("legalHoldReleaseReasons").toString()).isEqualTo(codeLabels(rule.get("legalHoldReleaseReasons")));
        assertThat(v.get("draftAbandonReasons").toString()).isEqualTo(codeLabels(rule.at("/draft/abandonReasons")));

        // 유형은 코드 순(룰 본문의 객체 키 — 저장된 JSONB에 순서가 없다), 해소 코드는 룰 순서
        List<String> types = new ArrayList<>();
        rule.at("/complianceQueue/types").properties().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> types.add("{\"resolutionCodes\":"
                + codeLabels(e.getValue().get("resolutionCodes")) + ",\"type\":\"" + e.getKey() + "\"}"));
        assertThat(v.get("flagTypes").toString()).isEqualTo("[" + String.join(",", types) + "]");

        List<String> enabled = new ArrayList<>();
        rule.get("channels").properties().forEach(e -> {
            if (e.getValue().get("enabled").asBoolean()) {
                enabled.add(e.getKey());
            }
        });
        List<String> channels = new ArrayList<>();
        v.get("channels").forEach(c -> channels.add(c.asString()));
        assertThat(channels).containsExactlyInAnyOrderElementsOf(enabled).doesNotContain("CERTIFIED_ESIGN");
        assertThat(v.get("signerRoles").toString()).isEqualTo(rule.get("signerSet").toString());
    }

    @Test
    void servicePrincipalsGetTheUnroutedResponse() {
        ApiTestSupport.Response noRoute = ApiTestSupport.get(port, "/api/v1/no-such-route", TestJwts.token(T, "scheduler-1"));
        assertThat(ApiTestSupport.get(port, PATH, TestJwts.token(T, "scheduler-1")).fingerprint()).isEqualTo(noRoute.fingerprint());
    }

    /** 목록은 화면의 선택지일 뿐 — 목록의 코드는 받고, 목록 밖 코드(직접 요청)는 어휘 API 전과 같은 거부다. */
    @Test
    void theServerStillDecides() {
        JsonNode v = Canonicalizer.parseStrict(ApiTestSupport.get(port, PATH, TestJwts.token(T, "compliance-1")).text());
        String listed = v.get("legalHoldReasons").get(0).get("code").asString();
        UUID[] drafts = new UUID[2];
        DB.seed(T, c -> {
            drafts[0] = SeedData.draftBy(c, T, "DEMO-AGENT-1", "/HQ/B1", "2026-09-23");
            drafts[1] = SeedData.draftBy(c, T, "DEMO-AGENT-1", "/HQ/B1", "2026-09-23");
        });
        String token = TestJwts.token(T, "compliance-1");
        ApiTestSupport.Response ok = ApiTestSupport.post(port, "/api/v1/legal-holds", token,
                "{\"disclosureId\":\"" + drafts[0] + "\",\"reasonCode\":\"" + listed + "\"}", Map.of("Idempotency-Key", "vocab-" + UUID.randomUUID()));
        assertThat(ok.status()).as(ok.text()).isEqualTo(201);
        ApiTestSupport.Response unlisted = ApiTestSupport.post(port, "/api/v1/legal-holds", token,
                "{\"disclosureId\":\"" + drafts[1] + "\",\"reasonCode\":\"NOT_IN_THE_VOCABULARY\"}", Map.of("Idempotency-Key", "vocab-" + UUID.randomUUID()));
        assertThat(v.toString()).doesNotContain("NOT_IN_THE_VOCABULARY");
        assertThat(unlisted.status()).isEqualTo(422);
        assertThat(unlisted.text()).contains("UNKNOWN_REASON");
    }

    @Test
    void aTenantWithoutRulesGetsTheSame503AsItsWrites() {
        String t = SeedData.uniqueTenant("VOCNR");
        DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.identityLink(c, t, "agent-1", "AGENT-1", "AGENT");
        });
        ApiTestSupport.Response r = ApiTestSupport.get(port, PATH, TestJwts.token(t, "agent-1"));
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.text()).contains("\"code\":\"TENANT_RULES_NOT_ACTIVE\"");
        ApiTestSupport.Response write = ApiTestSupport.post(port, "/api/v1/disclosures", TestJwts.token(t, "agent-1"), "{}",
                Map.of("Idempotency-Key", "vocab-" + UUID.randomUUID()));
        assertThat(r.fingerprint()).as("same bytes as the write path's 503").isEqualTo(write.fingerprint());
    }
}
