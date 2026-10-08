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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static com.ga.disclosure.app.api.ApiTestSupport.get;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 목록 커서(6A 계획 §4.2, G11): 쪽을 넘겨 전부 한 번씩, 다음 쪽이 없으면 {@code next}가 {@code null}. 다른 테넌트의 커서·다른 목록의 커서·변조·형식 오류는
 * 400 {@code INVALID_CURSOR}. {@code limit} 1~100. 커서 키 파일은 첫 기동에 소유자 전용으로 생긴다.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ListCursorIT {

    static final String T = SeedData.uniqueTenant("CURS");
    static final String OTHER = SeedData.uniqueTenant("CURSX");
    static final List<String> DAYS = List.of("2026-09-01", "2026-09-02", "2026-09-02", "2026-09-03", "2026-09-05");
    static final List<String> SEEDED = new ArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.orgLink(c, T, "agent-a", "A-1", "AGENT", "/HQ/B1");
            SeedData.roleLink(c, T, "compliance-1", "COMPLIANCE");
            SeedData.roleLink(c, T, "scheduler-1", "SCHEDULER");
            for (String day : DAYS) {
                SEEDED.add(SeedData.draftBy(c, T, "A-1", "/HQ/B1", day).toString());
            }
        });
        DB.seed(OTHER, c -> {
            SeedData.tenant(c, OTHER);
            SeedData.roleLink(c, OTHER, "compliance-x", "COMPLIANCE");
        });
    }

    @Value("${local.server.port}")
    int port;

    JsonNode page(String tenant, String subject, String query) {
        ApiTestSupport.Response r = get(port, "/api/v1/disclosures" + query, TestJwts.token(tenant, subject));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return Canonicalizer.parseStrict(r.text());
    }

    static String enc(String cursor) {
        return URLEncoder.encode(cursor, StandardCharsets.UTF_8);
    }

    @Test
    void pagesCoverEveryRowOnceInOrder() {
        List<String> seen = new ArrayList<>();
        String query = "?limit=2";
        int pages = 0;
        while (true) {
            JsonNode p = page(T, "agent-a", query);
            p.get("items").forEach(i -> seen.add(i.get("disclosureId").asString()));
            pages++;
            if (p.get("next").isNull()) {
                break;
            }
            query = "?limit=2&after=" + enc(p.get("next").asString());
        }
        assertThat(pages).isEqualTo(3);
        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(SEEDED);
        // 상담일 내림차순
        List<String> byDayDesc = new ArrayList<>();
        for (int i = DAYS.size() - 1; i >= 0; i--) {
            byDayDesc.add(DAYS.get(i));
        }
        List<String> seenDays = seen.stream().map(id -> DAYS.get(SEEDED.indexOf(id))).toList();
        assertThat(seenDays).isEqualTo(byDayDesc);
    }

    @Test
    void foreignTamperedAndCrossListCursorsAre400() {
        String cursor = page(T, "compliance-1", "?limit=1").get("next").asString();
        ApiTestSupport.Response foreign = get(port, "/api/v1/disclosures?after=" + enc(cursor), TestJwts.token(OTHER, "compliance-x"));
        assertThat(foreign.status()).isEqualTo(400);
        assertThat(foreign.text()).isEqualTo("{\"code\":\"INVALID_CURSOR\",\"details\":{},\"message\":\"The cursor is not valid.\"}");
        String tampered = cursor.substring(0, cursor.indexOf('.') - 2) + "AA" + cursor.substring(cursor.indexOf('.'));
        assertThat(get(port, "/api/v1/disclosures?after=" + enc(tampered), TestJwts.token(T, "compliance-1")).fingerprint())
                .isEqualTo(foreign.fingerprint());
        assertThat(get(port, "/api/v1/disclosures?after=not-a-cursor", TestJwts.token(T, "compliance-1")).fingerprint())
                .isEqualTo(foreign.fingerprint());
        // 작업 목록의 커서로 확인서 목록을 넘길 수 없다
        DB.seed(T, c -> {
            SeedData.asyncJob(c, T, "EXPIRE");
            SeedData.asyncJob(c, T, "RECONCILE");
        });
        ApiTestSupport.Response jobs = get(port, "/internal/v1/jobs?limit=1", TestJwts.token(T, "scheduler-1"));
        String jobCursor = Canonicalizer.parseStrict(jobs.text()).get("next").asString();
        assertThat(get(port, "/api/v1/disclosures?after=" + enc(jobCursor), TestJwts.token(T, "compliance-1")).fingerprint())
                .isEqualTo(foreign.fingerprint());
        assertThat(get(port, "/internal/v1/jobs?limit=1&after=" + enc(jobCursor), TestJwts.token(T, "scheduler-1")).status()).isEqualTo(200);
        // 위치 형식이 같은 목록(작업·보류 모두 "시각|ID")이라도 목록 종류가 MAC 안에 있으므로 열리지 않는다
        assertThat(get(port, "/api/v1/legal-holds?after=" + enc(jobCursor), TestJwts.token(T, "compliance-1")).fingerprint())
                .isEqualTo(foreign.fingerprint());
    }

    @Test
    void limitAndStatusAreValidated() throws Exception {
        for (String q : List.of("?limit=0", "?limit=101", "?limit=x")) {
            ApiTestSupport.Response r = get(port, "/api/v1/disclosures" + q, TestJwts.token(T, "agent-a"));
            assertThat(r.status()).as(q).isEqualTo(400);
            assertThat(r.text()).contains("\"field\":\"limit\"");
        }
        assertThat(get(port, "/api/v1/disclosures?status=BOGUS", TestJwts.token(T, "agent-a")).text()).contains("\"field\":\"status\"");
        assertThat(page(T, "agent-a", "?status=DRAFT&limit=100").get("items")).hasSize(DAYS.size());
        assertThat(Files.getPosixFilePermissions(ApiTestSupport.CURSOR_KEY))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }
}
