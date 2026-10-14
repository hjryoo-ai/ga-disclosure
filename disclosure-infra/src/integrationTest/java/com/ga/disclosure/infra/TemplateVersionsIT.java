package com.ga.disclosure.infra;

import com.ga.disclosure.compliance.rules.DistributionOutcome;
import com.ga.disclosure.compliance.rules.GovernanceRejectedException;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.rules.bundle.BundleLoader;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.seal.renderer.DisclosurePdfRenderer;
import com.ga.disclosure.seal.renderer.RendererVersion;
import com.ga.disclosure.seal.renderer.SignatureAppearance;
import com.ga.disclosure.seal.renderer.SignedPdfAppender;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.platform.canonical.Canonicalizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 7 0단계(G0): 서식 원문이 오면 <b>코드 변경 0으로 새 서식 버전만 배포</b>하면 된다는 전제의 증명. 라벨만 다른 가상 서식 버전
 * ({@code rule-as-data/templates/STANDARD-v2-alt-labels.bundle.json} — 식별부 라벨 4개와 문서 제목만 v1과 다르다, {@code TODO(confirm#2)}는 그대로)을
 * 운영과 같은 배포 경로({@code RuleDistributionService})로 새 버전으로 배포한다.
 * <ol>
 *   <li>기존 봉인 문서는 고정된 v1으로 남는다 — 저장된 PDF 바이트 불변, DB에서 읽은 v1로 다시 렌더하면 저장본과 바이트 동일, 배포 뒤 완료한 서명본도 v1
 *       라벨이고 봉인 PDF가 바이트 접두. 골든 PDF 3건과 서명본 골든은 <b>DB에서 읽은 v1</b>로 렌더해도 커밋된 SHA-256과 같다.</li>
 *   <li>배포 뒤 새 문서는 새 버전에 고정되고 새 라벨로 렌더된다(PDF 본문 텍스트).</li>
 *   <li>두 버전 공존 — 상담일 기준 해석이 구간대로 갈리고, 두 문서가 각자 버전으로 다시 렌더된다.</li>
 *   <li>제자리 수정 거부 — 같은 버전을 다른 본문으로 다시 배포하면 거부되고 아무것도 바뀌지 않는다(Phase 1 C5의 서식 판; DB 가드는
 *       {@code FormTemplateGuardIT}).</li>
 * </ol>
 * Phase 8(승인 Q9) — (서식 v1/v2 × 렌더러 판 1/2) 매트릭스: 서식은 정본 계약 {@code STANDARD-v2}(해약환급예시 표 열 정의)로, 허용 v1×1·v1×2·v2×2,
 * 거부 v2×1. 판 1이 봉인한 문서(Phase 3B~7 문서)는 업그레이드 뒤에도 판 1로 완료·재렌더된다.
 */
class TemplateVersionsIT {

    static final String ALT = "templates/STANDARD-v2-alt-labels.bundle.json";
    static final TemplateRef V1 = TemplateRef.of("STANDARD", 1);
    static final TemplateRef V2 = TemplateRef.of("STANDARD", 2);

    private final SignSetup x = new SignSetup();

    @AfterEach
    void close() {
        x.close();
    }

    /** 서식의 식별부 라벨·제목(데이터에서 읽는다 — 시험에도 라벨 상수를 두지 않는다). */
    static List<String> headerLabels(String bundleText) {
        JsonNode body = Canonicalizer.parseStrict(bundleText).get("body");
        List<String> labels = new ArrayList<>();
        body.get("fields").forEach(f -> {
            if (f.get("section").asString().equals("HEADER")) {
                labels.add(f.get("label").asString());
            }
        });
        labels.add(body.get("layout").get("title").asString());
        return labels;
    }

    byte[] artifact(DisclosureId id, ArtifactKind kind) {
        return ((ArtifactService.View.Granted) x.s.artifacts.view(Callers.of(x.w.tenant, SealSetup.COMPLIANCE), id, kind)).plaintext();
    }

    String pinned(DisclosureId id) {
        return x.s.text("SELECT template_id || ' v' || template_version FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?",
                x.w.tenant.value(), id.value());
    }

    String disclosureNo(DisclosureId id) {
        return x.s.text("SELECT disclosure_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", x.w.tenant.value(), id.value());
    }

    /** 문서에 고정된 렌더러 판(V23 — 산출물 행). 재렌더는 이 판으로 한다. */
    RendererVersion rendererOf(DisclosureId id) {
        return RendererVersion.of(Integer.parseInt(x.s.text(
                "SELECT renderer_version::text FROM document_artifact WHERE tenant_id = ? AND disclosure_id = ? AND kind = 'PDF'",
                x.w.tenant.value(), id.value())));
    }

    TemplateResolution load(TemplateRef ref) {
        return x.w.in(() -> new Governance().templateResolver.load(x.w.tenant, ref));
    }

    static String text(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String templateRows() {
        return x.s.text("SELECT string_agg(template_id || ' v' || version || ' ' || apply_from || '..' || coalesce(apply_to::text, '-') || ' '"
                + " || bundle_hash, ', ' ORDER BY version) FROM form_template WHERE tenant_id = ?", x.w.tenant.value());
    }

    @Test
    void aNewLabelsOnlyVersionChangesNewDocumentsAndLeavesSealedOnesOnTheirPinnedVersion() {
        // 배포 전: 문서 A를 v1으로 봉인
        DisclosureId a = x.sealed();
        assertThat(pinned(a)).isEqualTo("STANDARD v1");
        byte[] pdfA = artifact(a, ArtifactKind.PDF);
        byte[] canonicalA = artifact(a, ArtifactKind.CANONICAL_JSON);

        // 라벨만 다른 새 버전 배포(운영 경로) — v1은 새 개시일로 닫힌다
        DistributionOutcome deployed = new Governance().distribution.distribute(BundleFiles.fixture(ALT), x.w.tenant, Governance.OPERATOR);
        assertThat(deployed.result()).isEqualTo(DistributionOutcome.Result.INSERTED);
        assertThat(templateRows()).startsWith("STANDARD v1 2026-07-01..2026-09-01 ").contains(", STANDARD v2 2026-09-01..- ");

        // 배포 뒤: 문서 B는 새 버전에 고정
        DisclosureId b = x.sealed();
        assertThat(pinned(b)).isEqualTo("STANDARD v2");
        byte[] pdfB = artifact(b, ArtifactKind.PDF);

        // ① 문서 A — 저장 바이트 불변, 고정 버전 v1로 다시 렌더하면 바이트 동일, 새 라벨 없음
        assertThat(artifact(a, ArtifactKind.PDF)).isEqualTo(pdfA);
        assertThat(new DisclosurePdfRenderer().render(rendererOf(a), CanonicalDocument.parse(canonicalA), load(V1), disclosureNo(a)).pdf())
                .as("A re-rendered with its pinned v1").isEqualTo(pdfA);
        String textA = text(pdfA);
        assertThat(textA).contains(headerLabels(com.ga.disclosure.rules.testing.Bundles.text(com.ga.disclosure.rules.testing.Bundles.STANDARD_V1)));
        headerLabels(BundleFiles.fixtureText(ALT)).forEach(l -> assertThat(textA).as("A carries no new label").doesNotContain(l));

        // ② 문서 B — 새 라벨로 렌더, 고정 버전 v2로 다시 렌더하면 저장본과 바이트 동일
        String textB = text(pdfB);
        headerLabels(BundleFiles.fixtureText(ALT)).forEach(l -> assertThat(textB).as("B shows the new label").contains(l));
        assertThat(new DisclosurePdfRenderer().render(rendererOf(b), CanonicalDocument.parse(artifact(b, ArtifactKind.CANONICAL_JSON)), load(V2),
                disclosureNo(b)).pdf())
                .as("B re-rendered with its pinned v2").isEqualTo(pdfB);

        // ③ 공존 — 상담일 기준 해석이 구간대로, A를 배포 뒤에 완료해도 서명본은 v1 문서(봉인 PDF가 바이트 접두, 새 라벨 없음)
        TemplateRef on20260831 = x.w.in(() -> new Governance().templateResolver.resolve(x.w.tenant, TemplateType.STANDARD,
                LocalDate.parse("2026-08-31")).ref());
        assertThat(on20260831).isEqualTo(V1);
        TemplateRef on20260901 = x.w.in(() -> new Governance().templateResolver.resolve(x.w.tenant, TemplateType.STANDARD,
                LocalDate.parse("2026-09-01")).ref());
        assertThat(on20260901).isEqualTo(V2);
        assertThat(x.customerSignsOnTouchPad(a).accepted()).isTrue();
        x.clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(x.agentSigns(a).accepted()).isTrue();
        x.clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(x.managerConfirms(a).completed()).isTrue();
        byte[] signedA = artifact(a, ArtifactKind.SIGNED_PDF);
        assertThat(Arrays.equals(signedA, 0, pdfA.length, pdfA, 0, pdfA.length)).as("sealed v1 PDF is a byte prefix").isTrue();
        String signedText = text(signedA);
        headerLabels(BundleFiles.fixtureText(ALT)).forEach(l -> assertThat(signedText).doesNotContain(l));
        assertThat(artifact(a, ArtifactKind.PDF)).isEqualTo(pdfA);
    }

    @Test
    void theCommittedGoldensStillMatchWhenRenderedWithTheV1TemplateReadFromTheDatabaseAfterTheNewVersion() {
        new Governance().distribution.distribute(BundleFiles.fixture(ALT), x.w.tenant, Governance.OPERATOR);
        TemplateResolution v1 = load(V1);
        Path golden = Path.of(System.getProperty("ga.repoRoot"), "disclosure-seal", "src", "test", "resources", "golden");
        for (String name : List.of("case-01", "case-02", "case-03")) {
            Map<String, String> in = properties(golden.resolve(name).resolve("input.properties"));
            assertThat(in.get("templateId") + " v" + in.get("templateVersion")).isEqualTo("STANDARD v1");
            DisclosurePdfRenderer.Rendered pdf = new DisclosurePdfRenderer().render(RendererVersion.V1, canonical(golden.resolve(name)), v1,
                    in.get("disclosureNo"));
            assertThat(pdf.sha256()).as(name).isEqualTo(properties(golden.resolve(name).resolve("expected.properties")).get("pdfSha256"));
        }
        // 서명본 골든(signed-01): 바탕 사례의 봉인 PDF + 서명 페이지, 둘 다 DB의 v1로
        Path signed = golden.resolve("signed-01");
        JsonNode spec = Canonicalizer.parseStrict(read(signed.resolve("signatures.json")));
        Path base = golden.resolve(spec.path("base").asString());
        String no = properties(base.resolve("input.properties")).get("disclosureNo");
        byte[] original = new DisclosurePdfRenderer().render(RendererVersion.V1, canonical(base), v1, no).pdf();
        DisclosurePdfRenderer.Rendered signedPdf = new SignedPdfAppender().append(RendererVersion.V1, original, canonical(base), v1, no,
                appearances(spec, signed));
        assertThat(signedPdf.sha256()).isEqualTo(properties(signed.resolve("expected.properties")).get("signedPdfSha256"));
    }

    /**
     * 생산자 문자열 — 정보 사전 {@code /Producer}와 최신 XMP {@code pdf:Producer}가 같을 때만 그 값(PDF/A 일치 요건). 서명본의 정보 사전은 봉인 PDF에서
     * 이어 오고 XMP는 갱신분이 새로 쓰므로, 덧붙임이 다른 판으로 그려지면 둘이 갈린다.
     */
    static String producer(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String info = doc.getDocumentInformation().getProducer();
            String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            String inXmp = xmp.substring(xmp.indexOf("<pdf:Producer>") + "<pdf:Producer>".length(), xmp.indexOf("</pdf:Producer>"));
            assertThat(inXmp).as("XMP pdf:Producer = /Producer").isEqualTo(info);
            return info;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 표 머리글(데이터에서 읽는다): 정본 STANDARD-v2 해약환급예시의 열 라벨. */
    static List<String> columnLabels() {
        List<String> labels = new ArrayList<>();
        Canonicalizer.parseStrict(com.ga.disclosure.rules.testing.Bundles.text(com.ga.disclosure.rules.testing.Bundles.STANDARD_V2)).get("body")
                .get("fields").forEach(f -> f.path("render").path("columns").forEach(c -> labels.add(c.get("label").asString())));
        assertThat(labels).isNotEmpty();
        return labels;
    }

    /**
     * 매트릭스(승인 Q9). 정본 v2(개시 2026-10-01)를 v1과 함께 배포한 테넌트, 시계 2026-10-05: 상담일 9/23 문서는 v1, 10/5 문서는 v2에 고정된다.
     * 허용 — v1×2(현재 판의 봉인), v2×2(현재 판의 봉인, 표 머리글이 데이터의 열 라벨), v1×1(판 1 봉인). 거부 — v2×1: 판 1은 열 정의를 그리지 않는다
     * (렌더러가 거부하고 봉인은 아무것도 남기지 않는다). 각 허용 칸은 저장된 판·DB의 서식으로 다시 렌더하면 저장본과 바이트 동일하고, PDF 생산자 문자열이
     * 판을 적는다.
     */
    @Test
    void templateAndRendererMatrixAllowsV1OnBothRenderersAndV2OnlyOnRenderer2() {
        try (SealSetup m = new SealSetup(new WorkflowSetup("2026-10-05T01:00:00Z",
                new com.ga.disclosure.infra.engine.EngineClientSettings(java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(3), 3),
                List.of(com.ga.disclosure.rules.testing.Bundles.DISC_2026_07, com.ga.disclosure.rules.testing.Bundles.DISC_2027_01,
                        com.ga.disclosure.rules.testing.Bundles.STANDARD_V1, com.ga.disclosure.rules.testing.Bundles.STANDARD_V2)))) {
            var agent = Callers.of(m.w.tenant, WorkflowSetup.AGENT);
            LocalDate before = WorkflowSetup.CONSULT;
            LocalDate after = LocalDate.parse("2026-10-05");
            record Cell(String name, DisclosureId id, TemplateRef template, RendererVersion renderer) {
            }
            List<Cell> allowed = new ArrayList<>();
            for (var c : List.of(new Object[] {"v1x2", before, RendererVersion.V2}, new Object[] {"v2x2", after, RendererVersion.V2},
                    new Object[] {"v1x1", before, RendererVersion.V1})) {
                DisclosureId id = m.w.reasonedOn(m.w.customer, (LocalDate) c[1]);
                SealService.Outcome o = m.sealWith((RendererVersion) c[2]).seal(agent, id);
                assertThat(o.sealed()).as((String) c[0]).isTrue();
                allowed.add(new Cell((String) c[0], id, c[1] == before ? V1 : V2, (RendererVersion) c[2]));
            }
            for (Cell c : allowed) {
                String pin = m.text("SELECT template_id || ' v' || template_version FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?",
                        m.w.tenant.value(), c.id().value());
                assertThat(pin).as(c.name()).isEqualTo(c.template().templateId() + " v" + c.template().version());
                String stored = m.text("SELECT string_agg(kind || ':' || renderer_version, ',' ORDER BY kind) FROM document_artifact"
                        + " WHERE tenant_id = ? AND disclosure_id = ?", m.w.tenant.value(), c.id().value());
                assertThat(stored).as(c.name()).isEqualTo("CANONICAL_JSON:" + c.renderer().number() + ",PDF:" + c.renderer().number());
                var caller = Callers.of(m.w.tenant, SealSetup.COMPLIANCE);
                byte[] pdf = ((ArtifactService.View.Granted) m.artifacts.view(caller, c.id(), ArtifactKind.PDF)).plaintext();
                byte[] canonical = ((ArtifactService.View.Granted) m.artifacts.view(caller, c.id(), ArtifactKind.CANONICAL_JSON)).plaintext();
                String no = m.text("SELECT disclosure_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", m.w.tenant.value(), c.id().value());
                TemplateResolution template = m.w.in(() -> new Governance().templateResolver.load(m.w.tenant, c.template()));
                assertThat(new DisclosurePdfRenderer().render(c.renderer(), CanonicalDocument.parse(canonical), template, no).pdf())
                        .as(c.name() + " re-rendered with its stored renderer and template").isEqualTo(pdf);
                assertThat(producer(pdf)).as(c.name()).isEqualTo(c.renderer().producer());
                String body = text(pdf);
                columnLabels().forEach(l -> {
                    if (c.template().equals(V2)) {
                        assertThat(body).as(c.name() + " draws the cash-value table").contains(l);
                    } else {
                        assertThat(body).as(c.name() + " has no table header").doesNotContain(l);
                    }
                });
            }

            // 거부 v2×1 — 렌더러가 거부, 봉인은 아무것도 남기지 않는다(문서는 REASONED, 산출물 0, 번호 미발급)
            DisclosureId refused = m.w.reasonedOn(m.w.customer, after);
            String numbersBefore = m.text("SELECT count(*)::text FROM disclosure WHERE tenant_id = ? AND disclosure_no IS NOT NULL", m.w.tenant.value());
            assertThatThrownBy(() -> m.sealWith(RendererVersion.V1).seal(agent, refused))
                    .hasStackTraceContaining("uses render.columns, which renderer 1 does not draw");
            assertThat(m.text("SELECT status || ':' || (SELECT count(*) FROM document_artifact a WHERE a.tenant_id = d.tenant_id"
                    + " AND a.disclosure_id = d.disclosure_id) FROM disclosure d WHERE d.tenant_id = ? AND d.disclosure_id = ?",
                    m.w.tenant.value(), refused.value())).isEqualTo("REASONED:0");
            assertThat(m.text("SELECT count(*)::text FROM disclosure WHERE tenant_id = ? AND disclosure_no IS NOT NULL", m.w.tenant.value()))
                    .isEqualTo(numbersBefore);
            // 같은 거부를 렌더러 단독으로도(봉인된 v2×2 문서의 정본 + DB의 v2)
            Cell v2x2 = allowed.get(1);
            byte[] canonical = ((ArtifactService.View.Granted) m.artifacts.view(Callers.of(m.w.tenant, SealSetup.COMPLIANCE), v2x2.id(),
                    ArtifactKind.CANONICAL_JSON)).plaintext();
            TemplateResolution v2 = m.w.in(() -> new Governance().templateResolver.load(m.w.tenant, V2));
            assertThatThrownBy(() -> new DisclosurePdfRenderer().render(RendererVersion.V1, CanonicalDocument.parse(canonical), v2, "DEMO1-2026-000001"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("which renderer 1 does not draw");
        }
    }

    /**
     * 업그레이드 경로(승인 Q9): 판 1이 봉인한 문서를 현재 판(2) 바이너리가 완료한다 — 서명본·증거 패키지는 저장된 판 1을 따른다(서명본은 봉인 PDF가
     * 바이트 접두, 생산자 문자열 판 1, 매니페스트 {@code rendererVersion} 1, 산출물 행 전부 판 1). 봉인 PDF는 판 1로 다시 렌더해 바이트 동일.
     */
    @Test
    void aDocumentSealedByRenderer1CompletesOnRenderer1AfterTheUpgrade() {
        DisclosureId old = x.w.reasoned(x.signer);
        assertThat(x.s.sealWith(RendererVersion.V1).seal(Callers.of(x.w.tenant, WorkflowSetup.AGENT), old).sealed()).isTrue();
        assertThat(rendererOf(old)).isEqualTo(RendererVersion.V1);
        byte[] pdf = artifact(old, ArtifactKind.PDF);
        assertThat(producer(pdf)).isEqualTo(RendererVersion.V1.producer());

        // 완료는 현재 판 바이너리(SignSetup의 서명 유스케이스 — 판을 고르지 않는다)
        assertThat(x.customerSignsOnTouchPad(old).accepted()).isTrue();
        x.clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(x.agentSigns(old).accepted()).isTrue();
        x.clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(x.managerConfirms(old).completed()).isTrue();

        byte[] signed = artifact(old, ArtifactKind.SIGNED_PDF);
        assertThat(Arrays.equals(signed, 0, pdf.length, pdf, 0, pdf.length)).as("sealed PDF is a byte prefix").isTrue();
        assertThat(producer(signed)).isEqualTo(RendererVersion.V1.producer());
        JsonNode manifest = Canonicalizer.parseStrict(new String(com.ga.disclosure.seal.evidence.EvidencePackageReader.entries(
                artifact(old, ArtifactKind.EVIDENCE_ZIP)).get("manifest.json"), StandardCharsets.UTF_8));
        assertThat(manifest.get("manifestVersion").asInt()).isEqualTo(2);
        assertThat(manifest.get("rendererVersion").asInt()).isEqualTo(1);
        assertThat(x.s.text("SELECT string_agg(DISTINCT renderer_version::text, ',') FROM document_artifact WHERE tenant_id = ? AND disclosure_id = ?",
                x.w.tenant.value(), old.value())).isEqualTo("1");
        assertThat(new DisclosurePdfRenderer().render(RendererVersion.V1, CanonicalDocument.parse(artifact(old, ArtifactKind.CANONICAL_JSON)), load(V1),
                disclosureNo(old)).pdf()).isEqualTo(pdf);
    }

    /** 같은 서식·버전, 다른 본문(제목 끝에 공백 하나 — 번들 ID는 새 본문 해시로 다시 계산해 번들 자체는 유효하다). */
    static com.ga.disclosure.rules.bundle.Bundle titleTouched(String name, String bundleText) {
        tools.jackson.databind.node.ObjectNode tree = (tools.jackson.databind.node.ObjectNode) Canonicalizer.parseStrict(bundleText);
        tools.jackson.databind.node.ObjectNode layout = (tools.jackson.databind.node.ObjectNode) tree.get("body").get("layout");
        layout.put("title", layout.get("title").asString() + " ");
        String hash = com.ga.platform.canonical.Sha256.ofCanonical(tree.get("body").toString());
        String id = tree.get("bundleId").asString();
        tree.put("bundleId", id.substring(0, id.indexOf('@') + 1) + hash.substring(0, 12));
        return BundleLoader.parse(name, tree.toString());
    }

    @Test
    void redistributingAnExistingVersionWithAChangedBodyIsRefusedAndChangesNothing() {
        new Governance().distribution.distribute(BundleFiles.fixture(ALT), x.w.tenant, Governance.OPERATOR);
        String before = templateRows();
        for (var edited : List.of(titleTouched("STANDARD-v1 touched", com.ga.disclosure.rules.testing.Bundles.text(com.ga.disclosure.rules.testing.Bundles.STANDARD_V1)),
                titleTouched("STANDARD-v2-alt touched", BundleFiles.fixtureText(ALT)))) {
            assertThatThrownBy(() -> new Governance().distribution.distribute(edited, x.w.tenant, Governance.OPERATOR))
                    .isInstanceOf(GovernanceRejectedException.class).hasMessageContaining("a changed template needs a new version");
        }
        assertThat(templateRows()).isEqualTo(before);
        // 같은 번들의 재배포는 NOOP(멱등)
        assertThat(new Governance().distribution.distribute(BundleFiles.fixture(ALT), x.w.tenant, Governance.OPERATOR).result())
                .isEqualTo(DistributionOutcome.Result.NOOP);
    }

    static CanonicalDocument canonical(Path dir) {
        return CanonicalDocument.parse(readBytes(dir.resolve("canonical.json")));
    }

    static List<SignatureAppearance> appearances(JsonNode spec, Path dir) {
        List<SignatureAppearance> out = new ArrayList<>();
        for (JsonNode s : spec.path("signatures")) {
            List<SignatureAppearance.IdentityResult> identity = new ArrayList<>();
            s.path("identity").forEach(r -> identity.add(new SignatureAppearance.IdentityResult(IdentityMethod.valueOf(r.path("method").asString()),
                    r.path("passed").asBoolean())));
            JsonNode image = s.path("image");
            out.add(new SignatureAppearance(SignerRole.valueOf(s.path("role").asString()), SignatureChannel.valueOf(s.path("channel").asString()),
                    SignatureMethod.valueOf(s.path("method").asString()), Instant.parse(s.path("signedAt").asString()), identity,
                    image.isString() ? readBytes(dir.resolve(image.asString())) : null));
        }
        return out;
    }

    static Map<String, String> properties(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : read(file).split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                out.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1));
            }
        }
        return out;
    }

    static String read(Path file) {
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
