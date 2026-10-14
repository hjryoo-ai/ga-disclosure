package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.rules.template.FormTemplate;
import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.seal.renderer.DisclosurePdfRenderer;
import com.ga.disclosure.seal.renderer.RerenderMain;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B S2: 봉인한 확인서를 저장소에서 복호화해 얻은 봉인 본문으로 <b>별도 JVM 두 개</b>에서 다시 렌더하면 봉인 때의 PDF와 바이트가 같다. 두 JVM은
 * 시간대·로케일·파일 인코딩이 다르다(Asia/Seoul·ko_KR·UTF-8 ↔ America/New_York·en_US·ISO-8859-1). 상담일 또는 항목 하나만 바꾸면 canonical·PDF
 * 해시가 모두 바뀐다.
 */
class RenderDeterminismIT {

    private final SealSetup s = new SealSetup();

    @AfterEach
    void close() {
        s.close();
    }

    private static byte[] rerenderInAnotherJvm(Path dir, String name, List<String> jvmOptions, Path canonical, Path template,
                                               String disclosureNo, int rendererVersion) throws Exception {
        Path out = dir.resolve(name + ".pdf");
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(RerenderMain.class.getName());
        command.addAll(List.of(canonical.toString(), template.toString(), "STANDARD", "STANDARD", "1", disclosureNo, out.toString(),
                Integer.toString(rendererVersion)));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve(name + ".log").toFile()).start();
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).as(name + " finished").isTrue();
        assertThat(p.exitValue()).as(name + " exit (log " + dir.resolve(name + ".log") + ")").isZero();
        return Files.readAllBytes(out);
    }

    @Test
    void storedDocumentRerendersByteForByteInTwoDifferentJvms() throws Exception {
        SealService.Outcome o = s.sealReasoned();
        DisclosureId id = o.id();
        byte[] canonicalBytes = ((ArtifactService.View.Granted) s.artifacts.view(Callers.of(s.w.tenant, SealSetup.MANAGER), id, ArtifactKind.CANONICAL_JSON))
                .plaintext();
        byte[] sealedPdf = ((ArtifactService.View.Granted) s.artifacts.view(Callers.of(s.w.tenant, SealSetup.MANAGER), id, ArtifactKind.PDF)).plaintext();
        FormTemplate pinned = s.w.in(() -> s.w.templates.findByRef(s.w.tenant, com.ga.disclosure.domain.vo.TemplateRef.of("STANDARD", 1))
                .orElseThrow());
        Path dir = Files.createTempDirectory("ga-rerender");
        Path canonical = Files.write(dir.resolve("canonical.json"), canonicalBytes);
        Path template = Files.write(dir.resolve("template.json"), Canonicalizer.canonicalize(pinned.body()));
        String no = o.number().orElseThrow().value();
        // 재렌더는 문서에 고정된 판으로(Phase 8 V23 — 산출물 행의 renderer_version)
        int rendererVersion = Integer.parseInt(s.text("SELECT renderer_version::text FROM document_artifact WHERE tenant_id = ? AND disclosure_id = ? AND kind = 'PDF'",
                s.w.tenant.value(), id.value()));

        byte[] seoul = rerenderInAnotherJvm(dir, "seoul", List.of("-Duser.timezone=Asia/Seoul", "-Duser.language=ko", "-Duser.country=KR",
                "-Dfile.encoding=UTF-8"), canonical, template, no, rendererVersion);
        byte[] newYork = rerenderInAnotherJvm(dir, "new-york", List.of("-Duser.timezone=America/New_York", "-Duser.language=en",
                "-Duser.country=US", "-Dfile.encoding=ISO-8859-1"), canonical, template, no, rendererVersion);
        assertThat(seoul).isEqualTo(sealedPdf);
        assertThat(newYork).isEqualTo(sealedPdf);
        assertThat(com.ga.platform.canonical.Sha256.of(sealedPdf)).isEqualTo(s.text(
                "SELECT pdf_hash FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", s.w.tenant.value(), id.value()));

        // 상담일 하나, 항목 하나만 바꿔도 두 해시가 모두 바뀐다
        DisclosurePdfRenderer renderer = new DisclosurePdfRenderer();
        com.ga.disclosure.rules.template.TemplateResolution resolution = com.ga.disclosure.rules.template.TemplateResolver.resolution(pinned);
        CanonicalDocument original = CanonicalDocument.parse(canonicalBytes);
        ObjectNode date = (ObjectNode) original.json();
        date.put("consultDate", "2026-09-24");
        ObjectNode item = (ObjectNode) original.json();
        ((ObjectNode) item.path("items").get(0)).put("productName", "(가상) 다른 상품명");
        for (ObjectNode changed : List.of(date, item)) {
            CanonicalDocument other = CanonicalDocument.parse(Canonicalizer.canonicalize(changed));
            assertThat(other.sha256()).isNotEqualTo(original.sha256());
            assertThat(renderer.render(com.ga.disclosure.seal.renderer.RendererVersion.of(rendererVersion), other, resolution, no).sha256()).isNotEqualTo(com.ga.platform.canonical.Sha256.of(sealedPdf));
        }
    }
}
