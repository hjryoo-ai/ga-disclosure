package com.ga.disclosure.seal.golden;

import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.seal.canonical.CanonicalDocumentBuilder;
import com.ga.disclosure.seal.canonical.CanonicalInput;
import com.ga.disclosure.seal.canonical.SealFixtures;
import com.ga.disclosure.seal.renderer.DisclosurePdfRenderer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B S1: 골든 입력의 canonical·PDF SHA-256이 커밋된 기대값과 같다. 기대값은 로컬 macOS에서 만들었고(expected.properties의 생성 환경)
 * CI Linux가 같은 값을 내야 통과하므로 OS 간 결정론이 자동으로 검사된다. 시험 재료(SealFixtures)가 바뀌어 커밋된 canonical과 달라져도 실패한다.
 */
class SealGoldenTest {

    static final java.util.List<String> CASES = GoldenCase.NAMES;

    @ParameterizedTest
    @FieldSource("CASES")
    void canonicalAndPdfMatchTheCommittedExpectations(String name) {
        GoldenCase g = GoldenCase.of(name);
        Map<String, String> expected = g.expected();
        CanonicalDocument stored = g.canonical();
        assertThat(stored.sha256()).as(name + " canonical").isEqualTo(expected.get("canonicalSha256"));
        CanonicalInput in = switch (name) {
            case "case-01" -> SealFixtures.case01();
            case "case-02" -> SealFixtures.case02();
            case "case-03" -> SealFixtures.case03();
            default -> SealFixtures.case04();
        };
        assertThat(CanonicalDocumentBuilder.build(in, SealFixtures.NAME).bytes()).as(name + " builder = committed bytes").isEqualTo(stored.bytes());
        DisclosurePdfRenderer.Rendered pdf = new DisclosurePdfRenderer().render(g.rendererVersion(), stored, g.template(), g.disclosureNo());
        assertThat(pdf.sha256()).as(name + " pdf (" + pdf.pdf().length + " bytes)").isEqualTo(expected.get("pdfSha256"));
    }
}
