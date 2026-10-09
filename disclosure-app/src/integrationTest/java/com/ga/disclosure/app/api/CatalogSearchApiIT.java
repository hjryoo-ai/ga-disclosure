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

import java.util.ArrayList;
import java.util.List;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6B §9.8: {@code GET /api/v1/catalog/products?group&q&insurer} — 설계사·관리자·준법(행위 {@code CATALOG_READ} TENANT), 서비스 주체는 없는 라우트와 같은
 * 404. 기준일 오늘(KST)에 판매 중인 그 상품군만(판매 종료 상품 제외), 보험사·상품명 부분 일치로 좁힌다. 상품군 없음·형식 오류는 그 매개변수의 400.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogSearchApiIT {

    static final String T = SeedData.uniqueTenant("CATQ");

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

    ApiTestSupport.Response get(String subject, String path) {
        return ApiTestSupport.get(port, path, TestJwts.token(T, subject));
    }

    static List<String> keys(ApiTestSupport.Response r) {
        List<String> keys = new ArrayList<>();
        Canonicalizer.parseStrict(r.text()).get("items").forEach(i -> keys.add(i.get("productKey").asString()));
        return keys;
    }

    @Test
    void humanRolesSearchTodaysCatalogAndOthersGetTheSame404() {
        String base = "/api/v1/catalog/products?group=PG-HEALTH-SIMPLE-NR";
        for (String subject : List.of("agent-1", "manager-1", "compliance-1")) {
            ApiTestSupport.Response r = get(subject, base);
            assertThat(r.status()).as(subject).isEqualTo(200);
            // 판매 종료(2026-09-01) 상품은 없다, 상품 키 순
            assertThat(keys(r)).as(subject).contains("INS-A:PRD-1001", "INS-B:PRD-2044").doesNotContain("INS-B:PRD-2045", "INS-A:PRD-1101").isSorted();
        }
        JsonNode first = Canonicalizer.parseStrict(get("agent-1", base).text());
        assertThat(first.get("asOf").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(first.get("items").get(0).propertyNames()).containsExactlyInAnyOrder("productKey", "name", "insurerCode", "groupCode", "saleFrom",
                "saleTo");
        assertThat(keys(get("agent-1", base + "&insurer=INS-B"))).containsExactly("INS-B:PRD-2044");
        assertThat(keys(get("agent-1", base + "&q=%ED%94%8C%EB%9F%AC%EC%8A%A4"))).containsExactly("INS-A:PRD-1002");      // "플러스"
        assertThat(keys(get("agent-1", "/api/v1/catalog/products?group=PG-NOTHING"))).isEmpty();

        for (String[] bad : new String[][] {{"/api/v1/catalog/products", "group"}, {"/api/v1/catalog/products?group=pg-lower", "group"},
                {base + "&insurer=ins-b", "insurer"}, {base + "&q=" + "a".repeat(101), "q"}}) {
            ApiTestSupport.Response r = get("agent-1", bad[0]);
            assertThat(r.status()).as(bad[0]).isEqualTo(400);
            assertThat(Canonicalizer.parseStrict(r.text()).at("/details/field").asString()).isEqualTo(bad[1]);
        }
        ApiTestSupport.Response noRoute = get("scheduler-1", "/api/v1/no-such-route");
        assertThat(get("scheduler-1", base).fingerprint()).isEqualTo(noRoute.fingerprint());
    }
}
