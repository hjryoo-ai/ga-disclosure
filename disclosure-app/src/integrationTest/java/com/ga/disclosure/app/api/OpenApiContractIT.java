package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G11(6A 계획 §4.2): 계약 ↔ 라우트 양방향 — {@code /api}·{@code /internal}의 MVC 라우트 집합(메서드 + 경로, 변수 이름 무시)과 계약의 경로 집합(6B·대기 표시
 * 제외)이 같다. 계약에 {@code format} 키워드 0(형식은 {@code pattern}). 계약의 요청 예시는 요청 스키마를 통과한다. 그리고 검증기 자체가 틀린 모양을 잡는다 —
 * 다른 IT의 모든 응답은 {@link ApiTestSupport#send}에서 이 검증기를 지난다.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    static String normalize(String template) {
        return template.replaceAll("\\{[^/]+}", "{}");
    }

    @Test
    void routesAndContractAgreeBothWays() {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (pattern.startsWith("/api/") || pattern.startsWith("/internal/") || pattern.startsWith("/public/")) {
                    info.getMethodsCondition().getMethods().forEach(m -> routes.add(m.name() + " " + normalize(pattern)));
                }
            }
        }
        Set<String> contract = new TreeSet<>();
        ApiContracts.get().operations.forEach(o -> contract.add(o.method().toUpperCase() + " " + normalize(o.template())));
        assertThat(routes).as("MVC routes").isNotEmpty().isEqualTo(contract);
        // 6B: 계약에만 있던 표시 경로가 없다 — 옛 policy-link·게이트 GET stub은 계약에서 없앴고(계획 Q3·승인 §4) 계약 연결·게이트는 구현됐다
        assertThat(ApiContracts.get().unimplemented).isEmpty();
        assertThat(routes).contains("POST /internal/v1/contract-links", "POST /internal/v1/gate");
    }

    @Test
    void contractsUsePatternsNotFormats() {
        List<String> formats = new ArrayList<>();
        ApiContracts.get().docs.forEach((file, doc) -> walk(doc, file, formats));
        assertThat(formats).isEmpty();
    }

    private static void walk(JsonNode node, String where, List<String> out) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                if (e.getKey().equals("format")) {
                    out.add(where + "/format");
                }
                walk(e.getValue(), where + "/" + e.getKey(), out);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), where + "/" + i, out);
            }
        }
    }

    @Test
    void requestExamplesMatchTheirSchemas() {
        List<String> violations = new ArrayList<>();
        int examples = 0;
        for (ApiContracts.Operation o : ApiContracts.get().operations) {
            JsonNode media = o.node().path("requestBody").path("content").path("application/json");
            if (!media.has("example")) {
                continue;
            }
            examples++;
            String pointer = "/paths/" + o.template().replace("~", "~0").replace("/", "~1") + "/" + o.method() + "/requestBody/content/application~1json/schema";
            ApiContracts.get().schema(o.file(), pointer).validate(media.get("example"))
                    .forEach(e -> violations.add(o.method() + " " + o.template() + ": " + e));
        }
        assertThat(examples).isGreaterThanOrEqualTo(10);
        assertThat(violations).isEmpty();
    }

    @Test
    void theValidatorRejectsWrongShapes() {
        ApiContracts c = ApiContracts.get();
        byte[] extraField = "{\"code\":\"NOT_FOUND\",\"details\":{},\"message\":\"Resource not found.\",\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(c.violations("GET", "/api/v1/jobs/6b0c5a52-3f2e-4c1a-9f3b-2d1e0c9b8a7f", 404, "application/json", extraField)).isNotEmpty();
        byte[] unknownCode = "{\"code\":\"OOPS\",\"details\":{},\"message\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(c.violations("GET", "/api/v1/no-such-route", 404, "application/json", unknownCode)).isNotEmpty();
        byte[] receipt = "{\"disclosureId\":\"not-a-uuid\",\"status\":\"DRAFT\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(c.violations("POST", "/api/v1/disclosures", 201, "application/json", receipt)).isNotEmpty();
        assertThat(c.violations("GET", "/api/v1/disclosures/6b0c5a52-3f2e-4c1a-9f3b-2d1e0c9b8a7f/artifacts/PDF", 200, "text/html", new byte[] {1}))
                .as("undocumented media type").isNotEmpty();
        assertThat(c.violations("GET", "/actuator/health", 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8))).as("outside the contracts")
                .isEmpty();
    }
}
