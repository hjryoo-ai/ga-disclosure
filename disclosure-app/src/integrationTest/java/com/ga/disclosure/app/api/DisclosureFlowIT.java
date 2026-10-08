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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 확인서 쓰기 경로(6A 계획 §4.1)를 HTTP로 끝까지: 초안 201 → 항목 → 비교 → 산출(엔진 스텁) → 추천사유 → 검증 미리보기 → 봉인 → 산출물 PDF 바이트 → 앵커
 * 영수증(아직 덮이지 않음 409) → 정정은 HTTP에 없음(누구에게나 없는 라우트와 같은 404 — 6B 승인 §2) → 관리자 무효(ORG) → VOID. 거부는 범주로 — 상태 충돌 409, 업무 거부 422(코드·규칙 ID만), 형식 400, 남의 것 404.
 * 테넌트 준비(번들·카탈로그·가상 고객)는 운영자 CLI로 한다.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DisclosureFlowIT {

    static final String T = SeedData.uniqueTenant("FLOW");
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

    ApiTestSupport.Response post(String subject, String path, String json) {
        return ApiTestSupport.post(port, path, TestJwts.token(T, subject), json, Map.of("Idempotency-Key", "flow-" + UUID.randomUUID()));
    }

    ApiTestSupport.Response get(String subject, String path) {
        return ApiTestSupport.get(port, path, TestJwts.token(T, subject));
    }

    static JsonNode json(ApiTestSupport.Response r) {
        return Canonicalizer.parseStrict(r.text());
    }

    String draft() {
        ApiTestSupport.Response created = post("agent-1", "/api/v1/disclosures", "{\"customerRef\":\"" + customerRef
                + "\",\"groupCode\":\"PG-HEALTH-SIMPLE-NR\",\"consultDate\":\"2026-09-25\",\"templateType\":\"STANDARD\"}");
        assertThat(created.status()).as(created.text()).isEqualTo(201);
        String id = json(created).get("disclosureId").asString();
        assertThat(created.headers()).containsEntry("location", "/api/v1/disclosures/" + id);
        assertThat(json(created).get("status").asString()).isEqualTo("DRAFT");
        return id;
    }

    static final String ITEMS = FlowSupport.ITEMS;
    static final String REASONS = FlowSupport.REASONS;

    @Test
    void anAgentAuthorsAndSealsAndAManagerVoidsButCannotSupersede() {
        String id = draft();
        String base = "/api/v1/disclosures/" + id;
        assertThat(json(post("agent-1", base + "/items", ITEMS)).get("status").asString()).isEqualTo("DRAFT");
        assertThat(json(post("agent-1", base + "/compare", null)).get("status").asString()).isEqualTo("COMPARED");
        assertThat(json(post("agent-1", base + "/grades", null)).get("status").asString()).isEqualTo("GRADED");
        assertThat(json(post("agent-1", base + "/recommendations", REASONS)).get("status").asString()).isEqualTo("REASONED");

        ApiTestSupport.Response preview = post("agent-1", base + "/validate", "{\"stage\":\"SEAL\"}");
        assertThat(preview.status()).as(preview.text()).isEqualTo(200);
        assertThat(json(preview).get("results")).isNotEmpty().allSatisfy(r -> assertThat(r.has("ruleId")).isTrue());
        assertThat(preview.text()).doesNotContain("\"message\"");

        ApiTestSupport.Response sealed = post("agent-1", base + "/seal", null);
        assertThat(sealed.status()).as(sealed.text()).isEqualTo(200);
        assertThat(json(sealed).get("status").asString()).isEqualTo("SEALED");
        assertThat(json(sealed).get("disclosureNo").asString()).startsWith(T + "-2026-");

        ApiTestSupport.Response pdf = get("agent-1", base + "/artifacts/PDF");
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.headers().get("content-type")).isEqualTo("application/pdf");
        assertThat(new String(pdf.body(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(get("agent-x", base + "/artifacts/PDF").status()).as("another agent").isEqualTo(404);
        assertThat(get("agent-1", base + "/artifacts/BOGUS").status()).isEqualTo(404);

        // 앵커 영수증 내보내기는 준법 칸(설계사는 404)
        assertThat(get("agent-1", base + "/anchor-receipt").status()).isEqualTo(404);
        ApiTestSupport.Response receipt = get("compliance-1", base + "/anchor-receipt");
        assertThat(receipt.status()).as("not anchored yet — state, not request").isEqualTo(409);
        assertThat(receipt.text()).contains("\"code\":\"PACKAGE_UNAVAILABLE_NO_ARTIFACT\"");   // 증거 패키지는 서명·완료 뒤

        // 봉인된 확인서의 비교는 상태 충돌
        assertThat(post("agent-1", base + "/compare", null).status()).isEqualTo(409);
        // 정정은 HTTP에 없다(6B 승인 §2 — 운영자 CLI 대리 실행만): 설계사·관리자 모두 없는 라우트와 같은 404
        ApiTestSupport.Response noRoute = post("manager-1", "/api/v1/no-such-route", "{\"reasonCode\":\"CONTENT_ERROR\"}");
        for (String subject : new String[] {"agent-1", "manager-1"}) {
            ApiTestSupport.Response supersede = post(subject, base + "/supersede", "{\"reasonCode\":\"CONTENT_ERROR\"}");
            assertThat(supersede.status()).as(subject).isEqualTo(404);
            assertThat(supersede.fingerprint()).as(subject).isEqualTo(noRoute.fingerprint());
        }
        // 퇴사 등으로 정정할 문서는 관리자가 무효(ORG)하고 다른 설계사가 새로 작성한다. 모르는 사유 코드는 업무 거부 422
        ApiTestSupport.Response badReason = post("manager-1", base + "/void", "{\"reasonCode\":\"NOPE\"}");
        assertThat(badReason.status()).isEqualTo(422);
        assertThat(badReason.text()).isEqualTo("{\"code\":\"REJECTED\",\"details\":{\"rejections\":[{\"code\":\"REASON_CODE_UNKNOWN\"}]},"
                + "\"message\":\"The command was rejected.\"}");
        ApiTestSupport.Response voided = post("manager-1", base + "/void", "{\"reasonCode\":\"WRITTEN_IN_ERROR\"}");
        assertThat(voided.status()).as(voided.text()).isEqualTo(200);
        assertThat(json(get("agent-1", base)).get("status").asString()).isEqualTo("VOID");
    }

    @Test
    void validationBlocksAre422WithRuleIdsAndMalformedInputIs400() {
        String id = draft();
        String base = "/api/v1/disclosures/" + id;
        // 항목 없이 비교 — 단계 검증 차단(요청을 고쳐야 한다): 422, 규칙 ID만
        ApiTestSupport.Response blocked = post("agent-1", base + "/compare", null);
        assertThat(blocked.status()).as(blocked.text()).isEqualTo(422);
        JsonNode rejections = json(blocked).get("details").get("rejections");
        assertThat(rejections).isNotEmpty().allSatisfy(r -> {
            assertThat(r.get("code").asString()).isEqualTo("VALIDATION_BLOCKED");
            assertThat(r.get("ruleId").asString()).startsWith("R-");
        });
        assertThat(post("agent-1", base + "/items", "{\"items\":[{\"productKey\":\"no colon\"}]}").text()).contains("\"field\":\"items\"");
        assertThat(post("agent-1", "/api/v1/disclosures", "{\"customerRef\":\"" + customerRef + "\",\"groupCode\":\"PG-HEALTH-SIMPLE-NR\","
                + "\"consultDate\":\"2026-13-01\",\"templateType\":\"STANDARD\"}").text()).contains("\"field\":\"consultDate\"");
        assertThat(post("agent-1", base + "/validate", "{\"stage\":\"NOPE\"}").text()).contains("\"field\":\"stage\"");
        assertThat(post("agent-x", base + "/items", ITEMS).status()).as("another agent's draft").isEqualTo(404);
        assertThat(post("manager-1", base + "/void", "{\"reasonCode\":\"OTHER\"}").text()).contains("REASON_TEXT_REQUIRED");
        ApiTestSupport.Response voided = post("manager-1", base + "/void", "{\"reasonCode\":\"DUPLICATE\"}");
        assertThat(voided.status()).as(voided.text()).isEqualTo(200);
        assertThat(json(voided).get("status").asString()).isEqualTo("VOID");
    }

    @Test
    void aTempProductNeedsAManagerExceptionApprovalBoundToItsSubjectHash() {
        String id = draft();
        String base = "/api/v1/disclosures/" + id;
        String items = "{\"items\":[{\"productKey\":\"INS-A:PRD-1001\",\"recommended\":true},{\"productKey\":\"INS-B:PRD-2044\"},"
                + "{\"insurerCode\":\"INS-D\",\"productName\":\"(가상) 임시 상품\",\"quoteDocNo\":\"Q-2026-0001\"}]}";
        assertThat(post("agent-1", base + "/items", items).status()).isEqualTo(200);
        JsonNode results = json(post("agent-1", base + "/validate", "{\"stage\":\"SEAL\"}")).get("results");
        JsonNode temp = null;
        for (JsonNode r : results) {
            if (r.get("ruleId").asString().equals("R-TEMP-PRODUCT")) {
                temp = r;
            }
        }
        assertThat(temp).as("R-TEMP-PRODUCT in " + results).isNotNull();
        assertThat(temp.get("passed").asBoolean()).isFalse();
        assertThat(temp.get("overridable").asBoolean()).isTrue();
        String subjectHash = temp.get("subjectHash").asString();
        assertThat(subjectHash).matches("[0-9a-f]{64}");

        String approval = "{\"ruleId\":\"R-TEMP-PRODUCT\",\"subjectHash\":\"" + subjectHash + "\",\"reason\":\"발행번호 확인\"}";
        assertThat(post("agent-1", base + "/exception-approvals", approval).status()).as("the agent has no approval cell").isEqualTo(404);
        ApiTestSupport.Response approved = post("manager-1", base + "/exception-approvals", approval);
        assertThat(approved.status()).as(approved.text()).isEqualTo(200);
        assertThat(json(approved).get("ruleId").asString()).isEqualTo("R-TEMP-PRODUCT");
        assertThat(approved.text()).doesNotContain("발행번호 확인");
        assertThat(post("manager-1", base + "/exception-approvals", "{\"ruleId\":\"R-TEMP-PRODUCT\"}").text()).contains("\"field\":\"subjectHash\"");
    }
}
