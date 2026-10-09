package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static com.ga.disclosure.app.api.ApiTestSupport.ROOT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 승인 Q3(넓힘): {@code GET /api/v1/disclosures/{id}/template}은 그 확인서가 <b>고정한</b> 서식 버전의 화면 문구만 준다 — 값은 서식 번들 데이터 그대로
 * (이 시험도 라벨을 리터럴로 쓰지 않고 번들 파일에서 읽는다), 상태·검증 결과는 없다(계약 {@code additionalProperties:false} — 모든 응답이 계약 검증기를
 * 지난다), {@code ETag} = 내용 해시. 범위는 상세와 같다(설계사 자기 것·관리자 조직·준법 테넌트, 그 밖 404). 감사 행은 따로 남기지 않는다. 새 서식 버전이 배포돼도
 * 이미 만든 초안은 고정 버전, 새 초안은 새 버전.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DisclosureTemplateApiIT {

    static final String T = SeedData.uniqueTenant("TPL");
    static final Path V1 = ROOT.resolve("contracts/rules/bundles/templates/STANDARD-v1.bundle.json");
    static final Path V2 = ROOT.resolve("disclosure-infra/src/integrationTest/resources/rule-as-data/templates/STANDARD-v2-alt-labels.bundle.json");
    static String customerRef;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void seed() {
        customerRef = FlowSupport.prepare(T);
    }

    @Value("${local.server.port}")
    int port;

    ApiTestSupport.Response get(String subject, String path) {
        return get(T, subject, path);
    }

    ApiTestSupport.Response get(String tenant, String subject, String path) {
        return ApiTestSupport.get(port, path, TestJwts.token(tenant, subject));
    }

    String draft() {
        return draft(T, customerRef);
    }

    String draft(String tenant, String customer) {
        ApiTestSupport.Response created = FlowSupport.post(port, tenant, "agent-1", "/api/v1/disclosures", "{\"customerRef\":\"" + customer
                + "\",\"groupCode\":\"PG-HEALTH-SIMPLE-NR\",\"consultDate\":\"2026-09-25\",\"templateType\":\"STANDARD\"}");
        assertThat(created.status()).as(created.text()).isEqualTo(201);
        return Canonicalizer.parseStrict(created.text()).get("disclosureId").asString();
    }

    static JsonNode bundle(Path file) {
        try {
            return Canonicalizer.parseStrict(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 번들 데이터에서 기대 응답(항목은 순서대로, 라벨 참조 표식은 빠진다)을 만든다. */
    static void assertMatchesBundle(JsonNode response, JsonNode bundle) {
        JsonNode body = bundle.get("body");
        assertThat(response.get("templateId").asString()).isEqualTo(bundle.get("templateId").asString());
        assertThat(response.get("version").asInt()).isEqualTo(bundle.get("version").asInt());
        assertThat(response.get("bundleHash").asString()).isEqualTo(Sha256.of(Canonicalizer.canonicalize(body)));
        assertThat(response.get("pinned").asBoolean()).isTrue();
        assertThat(response.get("title").asString()).isEqualTo(body.at("/layout/title").asString());
        List<JsonNode> expected = new ArrayList<>();
        body.get("fields").forEach(expected::add);
        expected.sort(Comparator.comparingInt(f -> f.get("order").asInt()));
        JsonNode fields = response.get("fields");
        assertThat(fields).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode e = expected.get(i);
            JsonNode a = fields.get(i);
            assertThat(a.get("code").asString()).isEqualTo(e.get("code").asString());
            assertThat(a.get("label").asString()).isEqualTo(e.get("label").asString());
            assertThat(a.get("required").asBoolean()).isEqualTo(e.get("required").asBoolean());
            assertThat(a.get("order").asInt()).isEqualTo(e.get("order").asInt());
            assertThat(a.get("section").asString()).isEqualTo(e.get("section").asString());
            JsonNode unavailable = e.at("/render/unavailableText");
            assertThat(a.get("unavailableText").isNull()).isEqualTo(!unavailable.isString());
        }
        JsonNode sections = response.get("sections");
        assertThat(sections).hasSize(body.at("/layout/sections").size());
        for (int i = 0; i < sections.size(); i++) {
            assertThat(sections.get(i).get("code").asString()).isEqualTo(body.at("/layout/sections/" + i + "/code").asString());
            assertThat(sections.get(i).get("fields").toString()).isEqualTo(body.at("/layout/sections/" + i + "/fields").toString());
        }
        assertThat(response.toString()).doesNotContain("TODO(confirm#");
    }

    static long views(String targetId) {
        return DB.asApp(T, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM audit_log WHERE action = 'DISCLOSURE_VIEW' AND target_id = ?")) {
                ps.setString(1, targetId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Test
    void theResponseIsThePinnedTemplatesLabelsOnlyWithItsHashAsETag() {
        String id = draft();
        ApiTestSupport.Response r = get("agent-1", "/api/v1/disclosures/" + id + "/template");
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        JsonNode json = Canonicalizer.parseStrict(r.text());
        assertMatchesBundle(json, bundle(V1));
        assertThat(r.headers().get("etag")).isEqualTo("\"" + json.get("bundleHash").asString() + "\"");
        Set<String> names = new TreeSet<>();
        json.propertyNames().forEach(names::add);
        assertThat(names).containsExactly("bundleHash", "fields", "pinned", "sections", "templateId", "title", "version");
    }

    @Test
    void theScopeIsTheDetailsScopeAndNoAuditRowIsWritten() {
        String id = draft();
        String path = "/api/v1/disclosures/" + id + "/template";
        assertThat(get("manager-1", path).status()).isEqualTo(200);
        long before = views(id);
        assertThat(get("compliance-1", path).status()).isEqualTo(200);
        assertThat(views(id)).as("a template read writes no DISCLOSURE_VIEW row").isEqualTo(before);
        assertThat(get("compliance-1", "/api/v1/disclosures/" + id).status()).isEqualTo(200);
        assertThat(views(id)).as("the detail read does").isEqualTo(before + 1);

        ApiTestSupport.Response missing = get("agent-x", "/api/v1/disclosures/" + UUID.randomUUID() + "/template");
        ApiTestSupport.Response otherAgent = get("agent-x", path);
        assertThat(otherAgent.status()).isEqualTo(404);
        assertThat(otherAgent.fingerprint()).isEqualTo(missing.fingerprint());
    }

    @Test
    void aNewTemplateVersionChangesNewDraftsOnlyTheOldDraftKeepsItsPinnedLabels() {
        String t = SeedData.uniqueTenant("TPLV");                     // 새 버전 배포는 이 테넌트에만 — 다른 시험의 초안은 v1
        String customer = FlowSupport.prepare(t);
        String before = draft(t, customer);
        ApiTestSupport.cli("rules", "distribute", "--bundle", V2.toString(), "--tenants", t, "--operator", "tpl-it");
        String after = draft(t, customer);
        ApiTestSupport.Response old = get(t, "agent-1", "/api/v1/disclosures/" + before + "/template");
        assertMatchesBundle(Canonicalizer.parseStrict(old.text()), bundle(V1));
        ApiTestSupport.Response r = get(t, "agent-1", "/api/v1/disclosures/" + after + "/template");
        assertMatchesBundle(Canonicalizer.parseStrict(r.text()), bundle(V2));
        assertThat(r.headers().get("etag")).isNotEqualTo(old.headers().get("etag"));
    }
}
