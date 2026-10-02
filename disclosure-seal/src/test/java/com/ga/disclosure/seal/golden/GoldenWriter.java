package com.ga.disclosure.seal.golden;

import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.seal.canonical.CanonicalDocumentBuilder;
import com.ga.disclosure.seal.canonical.CanonicalInput;
import com.ga.disclosure.seal.canonical.SealFixtures;
import com.ga.disclosure.seal.renderer.DisclosurePdfRenderer;
import com.ga.platform.canonical.Canonicalizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 골든 입력·기대값 생성({@code ./gradlew :disclosure-seal:regenerateGolden}, 수동 전용 — 빌드·CI가 부르지 않는다). 기대값은 로컬(macOS)에서
 * 만들고 CI(Linux)가 {@code SealGoldenTest}로 검증한다 — OS 간 결정론 검사다. 기대값을 바꾸는 커밋은 메시지에 사유를 적는다.
 * {@code render <dir>} 모드는 커밋된 골든 입력으로 PDF를 써서 CI {@code pdfa-verify} 잡이 veraPDF로 검증하게 한다(Phase 4: 서명본 골든 포함).
 */
public final class GoldenWriter {

    private GoldenWriter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("render")) {
            Path out = Path.of(args[1]);
            Files.createDirectories(out);
            for (String name : GoldenCase.NAMES) {
                GoldenCase g = GoldenCase.of(name);
                byte[] pdf = new DisclosurePdfRenderer().render(g.canonical(), g.template(), g.disclosureNo()).pdf();
                Files.write(out.resolve(name + ".pdf"), pdf);
                System.out.println(name + ".pdf " + pdf.length + " bytes");
            }
            for (String name : SignedGolden.NAMES) {
                byte[] pdf = SignedGolden.of(name).sign().pdf();
                Files.write(out.resolve(name + ".pdf"), pdf);
                System.out.println(name + ".pdf " + pdf.length + " bytes");
            }
            return;
        }
        Map<String, CanonicalInput> cases = new LinkedHashMap<>();
        cases.put("case-01", SealFixtures.case01());
        cases.put("case-02", SealFixtures.case02());
        cases.put("case-03", SealFixtures.case03());
        int seq = 1;
        for (Map.Entry<String, CanonicalInput> e : cases.entrySet()) {
            GoldenCase g = GoldenCase.of(e.getKey());
            CanonicalDocument canonical = CanonicalDocumentBuilder.build(e.getValue(), SealFixtures.NAME);
            g.write("canonical.json", canonical.bytes());
            g.write("template.json", Canonicalizer.canonicalize(Bundles.template(Bundles.STANDARD_V1).body()));
            Map<String, String> input = new LinkedHashMap<>();
            input.put("disclosureNo", "DEMO1-2026-" + String.valueOf(1_000_000 + seq++).substring(1));
            input.put("templateId", e.getValue().template().templateId());
            input.put("templateVersion", Integer.toString(e.getValue().template().version()));
            g.write("input.properties", GoldenCase.lines(input).getBytes(StandardCharsets.UTF_8));
            GoldenCase written = GoldenCase.of(e.getKey());
            DisclosurePdfRenderer.Rendered pdf = new DisclosurePdfRenderer().render(written.canonical(), written.template(),
                    written.disclosureNo());
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put("canonicalSha256", canonical.sha256());
            expected.put("pdfSha256", pdf.sha256());
            expected.put("pdfBytes", Integer.toString(pdf.pdf().length));
            expected.put("generatedOn", environment());
            g.write("expected.properties", ("# 기대값 생성: ./gradlew :disclosure-seal:regenerateGolden(수동). 바꾸는 커밋은 사유를 메시지에 적는다.\n"
                    + GoldenCase.lines(expected)).getBytes(StandardCharsets.UTF_8));
            System.out.println(e.getKey() + " canonical=" + canonical.sha256() + " pdf=" + pdf.sha256());
        }
        for (String name : SignedGolden.NAMES) {
            SignedGolden g = SignedGolden.of(name);
            DisclosurePdfRenderer.Rendered signed = g.sign();
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put("signedPdfSha256", signed.sha256());
            expected.put("signedPdfBytes", Integer.toString(signed.pdf().length));
            expected.put("generatedOn", environment());
            g.files().write("expected.properties", ("# 기대값 생성: ./gradlew :disclosure-seal:regenerateGolden(수동). 바꾸는 커밋은 사유를 메시지에 적는다.\n"
                    + GoldenCase.lines(expected)).getBytes(StandardCharsets.UTF_8));
            System.out.println(name + " signedPdf=" + signed.sha256());
        }
    }

    private static String environment() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch") + ", "
                + System.getProperty("java.vendor") + " " + System.getProperty("java.version") + ", default locale " + java.util.Locale.getDefault()
                + ", zone " + java.util.TimeZone.getDefault().getID() + ", file.encoding " + System.getProperty("file.encoding");
    }
}
