package com.ga.disclosure.app.api;

import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.canonical.Canonicalizer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static com.ga.disclosure.app.api.ApiTestSupport.DEMO;
import static com.ga.disclosure.app.api.ApiTestSupport.ROOT;
import static org.assertj.core.api.Assertions.assertThat;

/** HTTP 쓰기 흐름 시험의 준비: 운영자 CLI로 번들·카탈로그·가상 고객을 넣은 테넌트, 그리고 HTTP로 봉인까지 간 확인서. */
final class FlowSupport {

    static final String ITEMS = "{\"items\":[{\"productKey\":\"INS-A:PRD-1001\",\"recommended\":true},{\"productKey\":\"INS-B:PRD-2044\"},"
            + "{\"productKey\":\"INS-C:PRD-3120\",\"recommended\":true}]}";
    static final String REASONS = "{\"reasons\":[{\"itemNo\":1,\"codes\":[\"PREMIUM\"]},{\"itemNo\":3,\"codes\":[\"COVERAGE\"]}]}";
    /** 좌표·시각 시퀀스(허구). */
    static final String STROKES = "[[{\"x\":131,\"y\":57,\"t\":0},{\"x\":140,\"y\":61,\"t\":16},{\"x\":152,\"y\":66,\"t\":33}]]";

    private FlowSupport() {
    }

    /** 링크(agent-1·manager-1 같은 조직, agent-x 다른 조직, compliance-1)·번들·카탈로그·가상 고객. 고객 C03의 가명 참조를 돌려준다. */
    static String prepare(String tenant) {
        return prepare(tenant, null);
    }

    /**
     * {@link #prepare(String)}과 같되 GLOBAL 룰 파일을 바꿀 수 있다({@code ruleBundleOrNull} — 저장소 밖 임시 파일, 예: 변형 번들).
     */
    static String prepare(String tenant, java.nio.file.Path ruleBundleOrNull) {
        DB.seed(tenant, c -> {
            SeedData.tenant(c, tenant);
            SeedData.identityLink(c, tenant, "agent-1", "DEMO-AGENT-1", "AGENT");
            SeedData.identityLink(c, tenant, "manager-1", "DEMO-MGR-1", "MANAGER");
            SeedData.orgLink(c, tenant, "agent-x", "DEMO-AGENT-X", "AGENT", "/HQ/B9");
            SeedData.roleLink(c, tenant, "compliance-1", "COMPLIANCE");
        });
        java.nio.file.Path bundles = ROOT.resolve("contracts/rules/bundles");
        for (java.nio.file.Path bundle : new java.nio.file.Path[] {ruleBundleOrNull == null ? bundles.resolve("rules/DISC-2026-07.bundle.json") : ruleBundleOrNull,
                bundles.resolve("templates/STANDARD-v1.bundle.json")}) {
            ApiTestSupport.cli("rules", "distribute", "--bundle", bundle.toString(), "--tenants", tenant, "--operator", "flow-it");
        }
        ApiTestSupport.cli("rules", "activate", "--as-of", "2026-09-23", "--tenants", tenant, "--operator", "flow-it");
        for (String file : new String[] {"product-groups.json", "insurer-panel.json", "products.json"}) {
            ApiTestSupport.cli("catalog", "import", "--tenant", tenant, "--file", DEMO.resolve("demo/catalog").resolve(file).toString(), "--operator",
                    "flow-it");
        }
        ApiTestSupport.cli("customer", "import", "--tenant", tenant, "--file", DEMO.resolve("customers.json").toString(), "--operator", "flow-it");
        return customerRef(tenant, "C03");
    }

    /** 가상 고객 파일의 고객 ID(C01·C02·C03)로 가명 참조를 찾는다(C01은 번호·생년월일이 있다 — 원격 링크·본인확인). */
    static String customerRef(String tenant, String id) {
        return DB.asApp(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT customer_ref FROM customer_ref WHERE registration_key = ?")) {
                ps.setString(1, "demo:customers.json#" + id);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getString(1);
                }
            }
        });
    }

    static ApiTestSupport.Response post(int port, String tenant, String subject, String path, String json) {
        return ApiTestSupport.post(port, path, TestJwts.token(tenant, subject), json, Map.of("Idempotency-Key", "flow-" + UUID.randomUUID()));
    }

    /** 설계사 agent-1이 HTTP로 초안부터 봉인까지 — 확인서 ID. */
    static String sealed(int port, String tenant, String customerRef) {
        ApiTestSupport.Response created = post(port, tenant, "agent-1", "/api/v1/disclosures", "{\"customerRef\":\"" + customerRef
                + "\",\"groupCode\":\"PG-HEALTH-SIMPLE-NR\",\"consultDate\":\"2026-09-25\",\"templateType\":\"STANDARD\"}");
        assertThat(created.status()).as(created.text()).isEqualTo(201);
        String id = Canonicalizer.parseStrict(created.text()).get("disclosureId").asString();
        String base = "/api/v1/disclosures/" + id;
        for (String[] step : new String[][] {{"/items", ITEMS}, {"/compare", null}, {"/grades", null}, {"/recommendations", REASONS}, {"/seal", null}}) {
            ApiTestSupport.Response r = post(port, tenant, "agent-1", base + step[0], step[1]);
            assertThat(r.status()).as(step[0] + " " + r.text()).isEqualTo(200);
        }
        return id;
    }

    /** 표준 GLOBAL 번들을 고친 변형(새 룰 버전 ID — 같은 ID로 내용이 다른 번들은 없다)을 임시 파일로. */
    static java.nio.file.Path variantBundle(String ruleVersionId, java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> edit) {
        try {
            tools.jackson.databind.node.ObjectNode bundle = (tools.jackson.databind.node.ObjectNode) Canonicalizer.parseStrict(
                    java.nio.file.Files.readString(ROOT.resolve("contracts/rules/bundles/rules/DISC-2026-07.bundle.json")));
            tools.jackson.databind.node.ObjectNode body = (tools.jackson.databind.node.ObjectNode) bundle.get("body");
            edit.accept(body);
            bundle.put("ruleVersionId", ruleVersionId);
            bundle.put("bundleId", ruleVersionId + "@" + com.ga.platform.canonical.Sha256.of(Canonicalizer.canonicalize(body)).substring(0, 12));
            java.nio.file.Path file = java.nio.file.Files.createTempDirectory("ga-variant").resolve(ruleVersionId + ".bundle.json");
            java.nio.file.Files.writeString(file, bundle.toString());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 설계사가 현장 기기 세션을 발급받아 토큰을 돌려준다. */
    static String deviceToken(int port, String tenant, String disclosureId, String channel) {
        ApiTestSupport.Response r = post(port, tenant, "agent-1", "/api/v1/disclosures/" + disclosureId + "/sign-sessions", "{\"channel\":\"" + channel + "\"}");
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        return Canonicalizer.parseStrict(r.text()).get("deviceToken").asString();
    }

    /** 고객 공개 경로 호출(토큰은 헤더 — {@code null}이면 보내지 않는다). */
    static ApiTestSupport.Response publicPost(int port, String path, String tokenOrNull, String json) {
        return ApiTestSupport.send(port, "POST", path, null, json, tokenOrNull == null ? Map.of() : Map.of("X-Sign-Token", tokenOrNull));
    }

    /** 작은 서명 PNG(검은 획 하나). */
    static byte[] png() {
        BufferedImage img = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 120; x++) {
            for (int y = 0; y < 40; y++) {
                img.setRGB(x, y, (y == 20 + (x % 7) - 3) ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
