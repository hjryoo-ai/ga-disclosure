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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static com.ga.disclosure.app.api.ApiTestSupport.get;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G10 ①(6A 수용심사 §2 ①): 플래그 목록은 관리자·준법 전용이다. 설계사는 자기 확인서의 플래그도 상세·목록 어디서도 못 본다 — 없는 확인서·없는 라우트와 같은
 * 404 바이트. 관리자는 작성 시점 조직 아래 확인서의 플래그만(확인서 없는 테넌트 수준 플래그는 빠진다), 준법은 테넌트 전체. 응답은 ID·유형·상태·열린 시각·대상
 * 확인서(ID·번호)뿐이고 최근에 열린 순, 상태·유형 필터와 서명된 커서.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FlagVisibilityIT {

    static final String T = SeedData.uniqueTenant("FLAG");
    static final String OTHER = SeedData.uniqueTenant("FLAGX");
    static UUID draftB1;
    static UUID draftB9;
    static UUID sealedB1;
    static UUID proxyFlag;
    static UUID scanFlag;
    static UUID overrideFlag;
    static UUID chainFlag;
    static String sealedNo;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    static UUID flag(Connection c, String tenant, String type, UUID disclosureOrNull, String raisedAt, boolean resolved) throws SQLException {
        UUID id = UUID.randomUUID();
        SeedData.exec(c, """
                INSERT INTO compliance_flag (tenant_id, flag_id, type, disclosure_id, severity, raised_at)
                VALUES (?, ?, ?, ?, 'HIGH', CAST(? AS timestamptz))
                """, tenant, id, type, disclosureOrNull, raisedAt);
        if (resolved) {
            // V14 GD134: 플래그는 열린 채로 생기고 해소는 그 뒤 한 번
            SeedData.exec(c, """
                    UPDATE compliance_flag SET resolved_at = raised_at + INTERVAL '1 hour', resolved_by = 'manager-1', resolution = 'PAPER_SCAN_REVIEWED'
                     WHERE tenant_id = ? AND flag_id = ?
                    """, tenant, id);
        }
        return id;
    }

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.orgLink(c, T, "agent-1", "AGENT-1", "AGENT", "/HQ/B1");
            SeedData.orgLink(c, T, "manager-1", null, "MANAGER", "/HQ/B1");
            SeedData.roleLink(c, T, "compliance-1", "COMPLIANCE");
            draftB1 = SeedData.draftBy(c, T, "AGENT-1", "/HQ/B1", "2026-09-01");
            draftB9 = SeedData.draftBy(c, T, "AGENT-9", "/HQ/B9", "2026-09-02");
            sealedB1 = SeedData.disclosure(c, T, "SEALED", SeedData.hash('c'));
            proxyFlag = flag(c, T, "SIGNATURE_DEVICE_REUSE", draftB1, "2026-09-10 09:00:00+09", false);
            scanFlag = flag(c, T, "PAPER_SCAN_REVIEW", sealedB1, "2026-09-11 09:00:00+09", true);
            overrideFlag = flag(c, T, "VALIDATION_OVERRIDE", draftB9, "2026-09-12 09:00:00+09", false);
            chainFlag = flag(c, T, "CHAIN_BROKEN", null, "2026-09-13 09:00:00+09", false);
        });
        sealedNo = DB.asApp(T, c -> SeedData.call(c, "SELECT disclosure_no FROM disclosure WHERE disclosure_id = ?", sealedB1));
        DB.seed(OTHER, c -> {
            SeedData.tenant(c, OTHER);
            SeedData.roleLink(c, OTHER, "compliance-x", "COMPLIANCE");
            flag(c, OTHER, "CHAIN_BROKEN", null, "2026-09-14 09:00:00+09", false);
        });
    }

    @Value("${local.server.port}")
    int port;

    @org.springframework.beans.factory.annotation.Autowired
    com.ga.disclosure.compliance.rules.RuleDistributionService distribution;

    @org.springframework.beans.factory.annotation.Autowired
    com.ga.disclosure.compliance.rules.RuleActivationJob activation;

    ApiTestSupport.Response as(String tenant, String subject, String path) {
        return get(port, path, TestJwts.token(tenant, subject));
    }

    static List<String> ids(ApiTestSupport.Response r) {
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        Canonicalizer.parseStrict(r.text()).get("items").forEach(i -> out.add(i.get("flagId").asString()));
        return out;
    }

    @Test
    void anAgentSeesNoFlagNotEvenOnItsOwnDisclosure() {
        ApiTestSupport.Response missingDisclosure = as(T, "agent-1", "/api/v1/disclosures/" + UUID.randomUUID() + "/flags");
        ApiTestSupport.Response noRoute = as(T, "agent-1", "/api/v1/no-such-route");
        assertThat(missingDisclosure.status()).isEqualTo(404);

        ApiTestSupport.Response own = as(T, "agent-1", "/api/v1/disclosures/" + draftB1 + "/flags");
        assertThat(own.status()).isEqualTo(404);
        assertThat(own.fingerprint()).isEqualTo(missingDisclosure.fingerprint());
        assertThat(own.text()).doesNotContain(proxyFlag.toString()).doesNotContain(draftB1.toString());
        ApiTestSupport.Response list = as(T, "agent-1", "/api/v1/flags");
        assertThat(list.status()).isEqualTo(404);
        assertThat(list.fingerprint()).isEqualTo(noRoute.fingerprint());
        // 확인서 상세에도 플래그가 없다 — 목록은 관리자·준법 전용 경로뿐
        ApiTestSupport.Response detail = as(T, "agent-1", "/api/v1/disclosures/" + draftB1);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.text()).doesNotContain(proxyFlag.toString()).doesNotContain("SIGNATURE_DEVICE_REUSE");
    }

    @Test
    void aManagerSeesFlagsOfDisclosuresUnderItsOrgOnly() {
        assertThat(ids(as(T, "manager-1", "/api/v1/flags"))).as("no /HQ/B9 flag, no tenant-level flag")
                .containsExactly(scanFlag.toString(), proxyFlag.toString());
        assertThat(ids(as(T, "manager-1", "/api/v1/disclosures/" + draftB1 + "/flags"))).containsExactly(proxyFlag.toString());
        ApiTestSupport.Response otherOrg = as(T, "manager-1", "/api/v1/disclosures/" + draftB9 + "/flags");
        assertThat(otherOrg.status()).isEqualTo(404);
        assertThat(otherOrg.fingerprint()).isEqualTo(as(T, "manager-1", "/api/v1/disclosures/" + UUID.randomUUID() + "/flags").fingerprint());
    }

    @Test
    void complianceSeesTheWholeTenantNewestFirstWithOnlyTheSummaryFields() {
        ApiTestSupport.Response all = as(T, "compliance-1", "/api/v1/flags");
        assertThat(ids(all)).containsExactly(chainFlag.toString(), overrideFlag.toString(), scanFlag.toString(), proxyFlag.toString());
        assertThat(all.body()).isEqualTo(Canonicalizer.canonicalize(all.text()));
        JsonNode items = Canonicalizer.parseStrict(all.text()).get("items");
        for (JsonNode item : items) {
            Set<String> names = new TreeSet<>();
            item.propertyNames().forEach(names::add);
            assertThat(names).containsExactly("disclosureId", "disclosureNo", "flagId", "raisedAt", "status", "type");
        }
        JsonNode chain = items.get(0);
        assertThat(chain.get("disclosureId").isNull()).isTrue();
        assertThat(chain.get("status").asString()).isEqualTo("OPEN");
        assertThat(chain.get("raisedAt").asString()).isEqualTo("2026-09-13T00:00:00Z");
        JsonNode scan = items.get(2);
        assertThat(scan.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(scan.get("disclosureId").asString()).isEqualTo(sealedB1.toString());
        assertThat(scan.get("disclosureNo").asString()).isEqualTo(sealedNo);
        assertThat(items.get(3).get("disclosureNo").isNull()).as("a draft has no number yet").isTrue();

        assertThat(ids(as(T, "compliance-1", "/api/v1/flags?status=OPEN")))
                .containsExactly(chainFlag.toString(), overrideFlag.toString(), proxyFlag.toString());
        assertThat(ids(as(T, "compliance-1", "/api/v1/flags?status=RESOLVED&type=PAPER_SCAN_REVIEW"))).containsExactly(scanFlag.toString());
        assertThat(ids(as(T, "compliance-1", "/api/v1/disclosures/" + draftB9 + "/flags"))).containsExactly(overrideFlag.toString());
        assertThat(as(T, "compliance-1", "/api/v1/flags?status=CLOSED").status()).isEqualTo(400);
        assertThat(as(T, "compliance-1", "/api/v1/flags?type=not-a-type").status()).isEqualTo(400);
        // 다른 테넌트의 준법은 자기 테넌트 플래그만
        assertThat(ids(as(OTHER, "compliance-x", "/api/v1/flags"))).hasSize(1).doesNotContain(chainFlag.toString());
        assertThat(as(OTHER, "compliance-x", "/api/v1/disclosures/" + draftB1 + "/flags").status()).isEqualTo(404);
    }

    /** 6B: 담당 역할·기한 필터, 배정·해소 경로도 설계사에게는 없는 라우트와 같은 404. */
    @Test
    void theQueueFiltersByAssignedRoleAndDueAndAgentsHaveNoCommandRoute() {
        String q = SeedData.uniqueTenant("FLAGQ");
        UUID[] ids = new UUID[3];
        DB.seed(q, c -> {
            SeedData.tenant(c, q);
            SeedData.orgLink(c, q, "agent-1", "AGENT-1", "AGENT", "/HQ/B1");
            SeedData.roleLink(c, q, "compliance-1", "COMPLIANCE");
            for (int i = 0; i < 3; i++) {
                ids[i] = UUID.randomUUID();
                SeedData.exec(c, """
                        INSERT INTO compliance_flag (tenant_id, flag_id, type, severity, raised_at, assigned_role, due_at)
                        VALUES (?, ?, 'SIGN_EXPIRED', 'HIGH', CAST(? AS timestamptz), ?, CAST(? AS timestamptz))""",
                        q, ids[i], "2026-09-1" + i + " 09:00:00+09", i == 1 ? "MANAGER" : "COMPLIANCE", i == 2 ? null : "2026-09-2" + i + " 09:00:00+09");
            }
        });
        ApiTestSupport.activateRules(distribution, activation, q, null, body -> {
        });                                                                // 쓰기 경로의 멱등 TTL은 룰에서 읽는다
        assertThat(ids(as(q, "compliance-1", "/api/v1/flags?assignedRole=MANAGER"))).containsExactly(ids[1].toString());
        assertThat(ids(as(q, "compliance-1", "/api/v1/flags?assignedRole=COMPLIANCE"))).containsExactly(ids[2].toString(), ids[0].toString());
        assertThat(ids(as(q, "compliance-1", "/api/v1/flags?dueBefore=2026-09-21T00:00:00Z"))).as("no-due flags are out")
                .containsExactly(ids[0].toString());
        assertThat(as(q, "compliance-1", "/api/v1/flags?assignedRole=AGENT").status()).isEqualTo(400);
        assertThat(as(q, "compliance-1", "/api/v1/flags?dueBefore=yesterday").status()).isEqualTo(400);
        ApiTestSupport.Response noRoute = ApiTestSupport.post(port, "/api/v1/no-such-route", TestJwts.token(q, "agent-1"), "{}",
                java.util.Map.of("Idempotency-Key", "it-" + UUID.randomUUID()));
        for (String path : new String[]{"/api/v1/flags/" + ids[0] + "/assign", "/api/v1/flags/" + ids[0] + "/resolve"}) {
            ApiTestSupport.Response agent = ApiTestSupport.post(port, path, TestJwts.token(q, "agent-1"),
                    path.endsWith("assign") ? "{\"assignee\":\"agent-1\"}" : "{\"resolutionCode\":\"REISSUED\"}",
                    java.util.Map.of("Idempotency-Key", "it-" + UUID.randomUUID()));
            assertThat(agent.status()).as(path).isEqualTo(404);
            assertThat(agent.fingerprint()).isEqualTo(noRoute.fingerprint());
        }
    }

    @Test
    void theListPagesWithASignedCursor() {
        ApiTestSupport.Response first = as(T, "compliance-1", "/api/v1/flags?limit=3");
        assertThat(ids(first)).containsExactly(chainFlag.toString(), overrideFlag.toString(), scanFlag.toString());
        String next = Canonicalizer.parseStrict(first.text()).get("next").asString();
        ApiTestSupport.Response second = as(T, "compliance-1", "/api/v1/flags?limit=3&after=" + next);
        assertThat(ids(second)).containsExactly(proxyFlag.toString());
        assertThat(Canonicalizer.parseStrict(second.text()).get("next").isNull()).isTrue();
        // 다른 목록(확인서)의 커서는 이 목록에서 열리지 않는다
        ApiTestSupport.Response disclosures = as(T, "compliance-1", "/api/v1/disclosures?limit=1");
        String foreign = Canonicalizer.parseStrict(disclosures.text()).get("next").asString();
        ApiTestSupport.Response wrongList = as(T, "compliance-1", "/api/v1/flags?after=" + foreign);
        assertThat(wrongList.status()).isEqualTo(400);
        assertThat(wrongList.text()).contains("\"code\":\"INVALID_CURSOR\"");
    }
}
