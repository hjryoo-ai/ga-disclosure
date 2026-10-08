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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static com.ga.disclosure.app.api.ApiTestSupport.get;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2(6A 계획 §3·§4.1): 범위 밖 확인서 — 설계사의 남의 것, 관리자의 다른 조직({@code /HQ} 대 {@code /HQX} 포함), 준법의 다른 테넌트 — 는 404이고 상태·헤더
 * (− Date)·본문이 없는 ID의 404와 바이트로 같다. 거부마다 {@code AUTHZ_DENIED} 1행, 응답에 대상 ID 없음. 목록은 같은 범위로 걸러진다(조직 없는 V12 이전 행은
 * 조직 범위 밖). 준법 조회는 요청마다 {@code DISCLOSURE_VIEW} 1행.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthzScopeIT {

    static final String T = SeedData.uniqueTenant("SCOPE");
    static final String OTHER = SeedData.uniqueTenant("SCOPEX");
    static UUID ownA;
    static UUID ownB;
    static UUID hqx;
    static UUID legacyA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void seed() throws Exception {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.orgLink(c, T, "agent-a", "A-1", "AGENT", "/HQ/B1");
            SeedData.orgLink(c, T, "agent-b", "A-2", "AGENT", "/HQ/B2");
            SeedData.orgLink(c, T, "manager-hq", null, "MANAGER", "/HQ");
            SeedData.roleLink(c, T, "compliance-1", "COMPLIANCE");
            ownA = SeedData.draftBy(c, T, "A-1", "/HQ/B1", "2026-09-01");
            ownB = SeedData.draftBy(c, T, "A-2", "/HQ/B2", "2026-09-02");
            hqx = SeedData.draftBy(c, T, "A-3", "/HQX/B1", "2026-09-03");
        });
        DB.seed(OTHER, c -> {
            SeedData.tenant(c, OTHER);
            SeedData.roleLink(c, OTHER, "compliance-x", "COMPLIANCE");
        });
        // V12 이전 행(조직 없음)은 GD124가 새로 만들지 못하게 한다 — 트리거를 끈 슈퍼유저 세션으로만 만든다
        legacyA = UUID.randomUUID();
        try (var c = DB.superuserDataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("SET session_replication_role = replica");
            s.executeUpdate("INSERT INTO disclosure (tenant_id, disclosure_id, org_path, agent_id, customer_ref, group_code, template_id, template_version,"
                    + " rule_version_id, issuer_mode, status, consult_date) VALUES ('" + T + "', '" + legacyA
                    + "', NULL, 'A-1', 'C-0001', 'PG-HEALTH', 'STANDARD', 1, 'DISC-2026-07', 'SELF', 'DRAFT', DATE '2026-08-01')");
        }
    }

    @Value("${local.server.port}")
    int port;

    ApiTestSupport.Response detail(String tenant, String subject, UUID id) {
        return get(port, "/api/v1/disclosures/" + id, TestJwts.token(tenant, subject));
    }

    static long audit(String tenant, String action, String targetIdOrNull) {
        return DB.asApp(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM audit_log WHERE action = ? AND target_id IS NOT DISTINCT FROM ?")) {
                ps.setString(1, action);
                ps.setString(2, targetIdOrNull);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    static List<String> ids(ApiTestSupport.Response page) {
        List<String> out = new ArrayList<>();
        Canonicalizer.parseStrict(page.text()).get("items").forEach(i -> out.add(i.get("disclosureId").asString()));
        return out;
    }

    @Test
    void outOfScopeDetailIsTheSame404AsAMissingIdAndIsAuditedOnce() {
        String missing = UUID.randomUUID().toString();
        record Case(String tenant, String subject, UUID target) {
        }
        List<Case> denied = List.of(
                new Case(T, "agent-a", ownB),          // 설계사: 남의 확인서
                new Case(T, "manager-hq", hqx),        // 관리자: /HQ는 /HQX를 덮지 않는다
                new Case(T, "manager-hq", legacyA),    // 관리자: 조직 없는 V12 이전 행
                new Case(OTHER, "compliance-x", ownA)); // 준법: 다른 테넌트
        for (Case k : denied) {
            ApiTestSupport.Response none = get(port, "/api/v1/disclosures/" + missing, TestJwts.token(k.tenant(), k.subject()));
            long before = audit(k.tenant(), "AUTHZ_DENIED", k.target().toString());
            ApiTestSupport.Response r = detail(k.tenant(), k.subject(), k.target());
            assertThat(r.status()).as(k.toString()).isEqualTo(404);
            assertThat(r.fingerprint()).as(k.toString()).isEqualTo(none.fingerprint());
            assertThat(r.text()).doesNotContain(k.target().toString());
            assertThat(audit(k.tenant(), "AUTHZ_DENIED", k.target().toString())).as(k.toString()).isEqualTo(before + 1);
        }
    }

    @Test
    void inScopeDetailIsReadAndComplianceReadsAreAudited() {
        assertThat(detail(T, "agent-a", ownA).status()).isEqualTo(200);
        assertThat(detail(T, "agent-a", legacyA).status()).as("own legacy row").isEqualTo(200);
        assertThat(detail(T, "manager-hq", ownB).status()).isEqualTo(200);
        long views = audit(T, "DISCLOSURE_VIEW", hqx.toString());
        ApiTestSupport.Response r = detail(T, "compliance-1", hqx);
        assertThat(r.status()).isEqualTo(200);
        assertThat(audit(T, "DISCLOSURE_VIEW", hqx.toString())).isEqualTo(views + 1);
        JsonNode body = Canonicalizer.parseStrict(r.text());
        assertThat(body.get("disclosureId").asString()).isEqualTo(hqx.toString());
        assertThat(body.get("status").asString()).isEqualTo("DRAFT");
        assertThat(body.get("customerRef").asString()).isEqualTo("C-0001");
        assertThat(body.has("asOf")).isTrue();
        assertThat(r.body()).isEqualTo(Canonicalizer.canonicalize(r.text()));
        // 설계사·관리자 조회는 감사하지 않는다(감사 의무는 준법의 전 건 조회)
        assertThat(audit(T, "DISCLOSURE_VIEW", ownA.toString())).isZero();
    }

    @Test
    void listsAreFilteredByTheSameScope() {
        assertThat(ids(get(port, "/api/v1/disclosures", TestJwts.token(T, "agent-a"))))
                .containsExactly(ownA.toString(), legacyA.toString());
        assertThat(ids(get(port, "/api/v1/disclosures", TestJwts.token(T, "manager-hq"))))
                .containsExactly(ownB.toString(), ownA.toString());
        long lists = audit(T, "DISCLOSURE_VIEW", null);
        assertThat(ids(get(port, "/api/v1/disclosures", TestJwts.token(T, "compliance-1"))))
                .containsExactly(hqx.toString(), ownB.toString(), ownA.toString(), legacyA.toString());
        assertThat(audit(T, "DISCLOSURE_VIEW", null)).isEqualTo(lists + 1);
        assertThat(ids(get(port, "/api/v1/disclosures", TestJwts.token(OTHER, "compliance-x")))).isEmpty();
        assertThat(ids(get(port, "/api/v1/disclosures?status=SEALED", TestJwts.token(T, "compliance-1")))).isEmpty();
    }
}
