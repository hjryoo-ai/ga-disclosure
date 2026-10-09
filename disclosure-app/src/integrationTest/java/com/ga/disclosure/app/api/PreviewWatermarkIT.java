package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.sign.DeviceInfo;
import com.ga.disclosure.workflow.sign.SignatureCapture;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static com.ga.disclosure.app.api.ApiTestSupport.ROOT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G7(Phase 7 계획 ⑥): 워터마크는 미리보기({@code GET …/preview.pdf})에만 있다.
 * <ul>
 *   <li>미리보기: 모든 쪽에 룰 {@code preview.watermark}의 문구(열람자 역할 표기 · 열람 시각 KST) — 기대 문구는 룰 번들 파일에서 만든다(리터럴 없음),
 *       쪽 수 = 봉인 PDF, {@code no-store}·{@code inline}.</li>
 *   <li>미리보기 전후로 {@code getArtifact}의 네 종류(CANONICAL_JSON·PDF·SIGNED_PDF·EVIDENCE_ZIP) 바이트 해시, 저장소 객체 수, {@code document_artifact} 행이
 *       같다(저장하지 않는다).</li>
 *   <li>공개 서명 화면의 PDF({@code /public/v1/sign/open})는 봉인 PDF와 바이트가 같다(워터마크 없음).</li>
 *   <li>감사 {@code ARTIFACT_VIEW} 목적 {@code PREVIEW}가 미리보기마다 1행, 범위는 산출물 열람과 같다(설계사 자기 것·관리자 조직·준법 테넌트, 그 밖 404),
 *       봉인 전은 409 {@code NO_ARTIFACT}.</li>
 * </ul>
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreviewWatermarkIT {

    static final String T = SeedData.uniqueTenant("PREV");
    static final List<String> KINDS = List.of("CANONICAL_JSON", "PDF", "SIGNED_PDF", "EVIDENCE_ZIP");
    static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
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

    @Autowired
    SignSessionService sessions;

    @Autowired
    SignService signing;

    @Autowired
    ArtifactStore storage;

    ApiTestSupport.Response post(String subject, String path, String json) {
        return FlowSupport.post(port, T, subject, path, json);
    }

    ApiTestSupport.Response get(String subject, String path) {
        return ApiTestSupport.get(port, path, TestJwts.token(T, subject));
    }

    static JsonNode json(ApiTestSupport.Response r) {
        return Canonicalizer.parseStrict(r.text());
    }

    /** 표준 GLOBAL 번들의 워터마크 룰(문구·역할 표기) — 시험이 문구를 리터럴로 쓰지 않는다. */
    static JsonNode watermarkRule() {
        try {
            return Canonicalizer.parseStrict(Files.readString(ROOT.resolve("contracts/rules/bundles/rules/DISC-2026-07.bundle.json")))
                    .at("/body/preview/watermark");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String expectedMark(String role, ZonedDateTime at) {
        JsonNode rule = watermarkRule();
        return rule.get("text").asString().replace("{role}", rule.at("/roleLabels/" + role).asString()).replace("{at}", MINUTE.format(at));
    }

    static List<String> pageTexts(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<String> pages = new java.util.ArrayList<>();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                PDFTextStripper s = new PDFTextStripper();
                s.setStartPage(i);
                s.setEndPage(i);
                pages.add(s.getText(doc).replaceAll("\\s", ""));        // 회전한 글자는 줄이 갈린다 — 공백을 지우고 본다
            }
            return pages;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Map<String, String> artifactHashes(String id) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String kind : KINDS) {
            ApiTestSupport.Response r = get("compliance-1", "/api/v1/disclosures/" + id + "/artifacts/" + kind);
            assertThat(r.status()).as(kind).isEqualTo(200);
            out.put(kind, Sha256.of(r.body()));
        }
        return out;
    }

    static long count(String sql, Object... args) {
        return DB.asApp(T, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** 봉인 → 현장 서명 세션 → 공개 경로로 서명 대상 PDF 열람(HTTP) → 고객·설계사·관리자 서명 → 완료. 공개 PDF 바이트를 돌려준다. */
    byte[] signedThroughCompletion(String id) {
        String base = "/api/v1/disclosures/" + id;
        String token = json(post("agent-1", base + "/sign-sessions", "{\"channel\":\"TOUCH_PAD\"}")).get("deviceToken").asString();
        assertThat(post("agent-1", "/api/v1/sign-sessions/face-to-face", "{\"token\":\"" + token + "\"}").status()).isEqualTo(200);
        ApiTestSupport.Response open = ApiTestSupport.post(port, "/public/v1/sign/open", null, "{}", Map.of("X-Sign-Token", token));
        assertThat(open.status()).isEqualTo(200);
        sessions.recordView(token, true, 30);
        signing.capture(token, new SignatureCapture(FlowSupport.STROKES.getBytes(StandardCharsets.UTF_8), FlowSupport.png(),
                new DeviceInfo(null, "Kiosk/1.0"), null));
        String agentSignature = "{\"strokes\":" + FlowSupport.STROKES + ",\"imagePngBase64\":\"" + Base64.getEncoder().encodeToString(FlowSupport.png())
                + "\"}";
        assertThat(post("agent-1", base + "/agent-signature", agentSignature).status()).isEqualTo(200);
        ApiTestSupport.Response confirmed = post("manager-1", base + "/manager-confirmation", "{\"acknowledgedFlags\":[]}");
        assertThat(json(confirmed).get("status").asString()).as(confirmed.text()).isEqualTo("COMPLETED");
        return open.body();
    }

    @Test
    void onlyThePreviewCarriesTheMarkAndNoArtifactOrStoredObjectChanges() {
        String id = FlowSupport.sealed(port, T, customerRef);
        byte[] publicPdf = signedThroughCompletion(id);
        byte[] sealedPdf = get("compliance-1", "/api/v1/disclosures/" + id + "/artifacts/PDF").body();
        assertThat(publicPdf).as("the customer signs the sealed PDF itself — no watermark").isEqualTo(sealedPdf);

        Map<String, String> before = artifactHashes(id);
        long objects = storage.list(T + "/").size();
        long rows = count("SELECT count(*) FROM document_artifact WHERE disclosure_id = ?", UUID.fromString(id));
        long previewsAudited = count("SELECT count(*) FROM audit_log WHERE action = 'ARTIFACT_VIEW' AND detail->>'reason' = 'PREVIEW'");
        int pages = pageTexts(sealedPdf).size();

        for (String[] who : new String[][] {{"agent-1", "AGENT"}, {"manager-1", "MANAGER"}, {"compliance-1", "COMPLIANCE"}}) {
            ZonedDateTime from = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
            ApiTestSupport.Response preview = get(who[0], "/api/v1/disclosures/" + id + "/preview.pdf");
            ZonedDateTime to = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
            assertThat(preview.status()).as(who[0] + " " + preview.text()).isEqualTo(200);
            assertThat(preview.headers().get("content-type")).isEqualTo("application/pdf");
            assertThat(preview.headers().get("cache-control")).contains("no-store");
            assertThat(preview.headers().get("content-disposition")).isEqualTo("inline");
            List<String> texts = pageTexts(preview.body());
            assertThat(texts).hasSize(pages);
            String a = expectedMark(who[1], from).replaceAll("\\s", "");
            String b = expectedMark(who[1], to).replaceAll("\\s", "");
            for (String page : texts) {
                assertThat(page.contains(a) || page.contains(b)).as(who[0] + " page carries the mark").isTrue();
            }
            assertThat(pageTexts(sealedPdf).getFirst()).doesNotContain(a);
        }

        assertThat(artifactHashes(id)).as("artifact bytes unchanged by previews").isEqualTo(before);
        assertThat((long) storage.list(T + "/").size()).as("no object stored").isEqualTo(objects);
        assertThat(count("SELECT count(*) FROM document_artifact WHERE disclosure_id = ?", UUID.fromString(id))).isEqualTo(rows);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'ARTIFACT_VIEW' AND detail->>'reason' = 'PREVIEW'"))
                .isEqualTo(previewsAudited + 3);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'ARTIFACT_VIEW' AND detail->>'reason' = 'SIGN' AND detail->>'disclosureId' = ?", id))
                .as("the public open is audited with purpose SIGN").isEqualTo(1);
    }

    @Test
    void theScopeIsTheArtifactViewScopeAndADraftHasNothingToPreview() {
        String id = FlowSupport.sealed(port, T, customerRef);
        ApiTestSupport.Response missing = get("agent-x", "/api/v1/disclosures/" + UUID.randomUUID() + "/preview.pdf");
        ApiTestSupport.Response otherAgent = get("agent-x", "/api/v1/disclosures/" + id + "/preview.pdf");
        assertThat(otherAgent.status()).isEqualTo(404);
        assertThat(otherAgent.fingerprint()).isEqualTo(missing.fingerprint());
        assertThat(get("agent-1", "/api/v1/disclosures/" + id + "/preview.pdf").status()).isEqualTo(200);

        ApiTestSupport.Response created = post("agent-1", "/api/v1/disclosures", "{\"customerRef\":\"" + customerRef
                + "\",\"groupCode\":\"PG-HEALTH-SIMPLE-NR\",\"consultDate\":\"2026-09-25\",\"templateType\":\"STANDARD\"}");
        String draft = json(created).get("disclosureId").asString();
        ApiTestSupport.Response none = get("agent-1", "/api/v1/disclosures/" + draft + "/preview.pdf");
        assertThat(none.status()).isEqualTo(409);
        assertThat(none.text()).contains("NO_ARTIFACT");
    }
}
