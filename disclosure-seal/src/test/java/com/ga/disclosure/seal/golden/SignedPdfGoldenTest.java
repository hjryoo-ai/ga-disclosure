package com.ga.disclosure.seal.golden;

import com.ga.disclosure.seal.renderer.DisclosurePdfRenderer;
import com.ga.disclosure.seal.renderer.SignatureAppearance;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G7: 서명본 = 봉인 PDF 원본 + 증분 갱신(3B 수용심사 §3-2). 원본이 바이트 접두, 2회 바이트 동일, 골든 SHA-256(로컬 macOS 생성 · CI Linux
 * 일치 — OS 간 결정론), 스파이크의 두 조건 재증명({@code /ID} 둘째 원소가 시각이 아니라 입력에서 온다, 폰트가 임베드된다 — CI
 * {@code pdfa-verify}가 서명본 골든을 veraPDF로 검사). 날짜는 마지막 서명 시각, {@code /ID} 첫 원소는 원본 그대로.
 */
class SignedPdfGoldenTest {

    static final List<String> CASES = SignedGolden.NAMES;

    @ParameterizedTest
    @FieldSource("CASES")
    void signedPdfMatchesTheCommittedExpectation(String name) {
        SignedGolden g = SignedGolden.of(name);
        DisclosurePdfRenderer.Rendered signed = g.sign();
        assertThat(signed.sha256()).as(name + " signed pdf (" + signed.pdf().length + " bytes)").isEqualTo(g.expected().get("signedPdfSha256"));
    }

    @ParameterizedTest
    @FieldSource("CASES")
    void sealedPdfIsAByteprefixAndTwoRunsAreIdentical(String name) {
        SignedGolden g = SignedGolden.of(name);
        byte[] original = g.originalPdf();
        byte[] first = g.sign(original, g.signatures()).pdf();
        byte[] second = g.sign(original, g.signatures()).pdf();
        assertThat(first.length).isGreaterThan(original.length);
        assertThat(Arrays.copyOf(first, original.length)).as("sealed PDF is a byte prefix of the signed PDF").isEqualTo(original);
        assertThat(second).isEqualTo(first);
    }

    @ParameterizedTest
    @FieldSource("CASES")
    void datesAndIdComeFromTheInputs(String name) throws Exception {
        SignedGolden g = SignedGolden.of(name);
        byte[] original = g.originalPdf();
        byte[] signed = g.sign(original, g.signatures()).pdf();
        try (PDDocument o = Loader.loadPDF(original); PDDocument s = Loader.loadPDF(signed)) {
            assertThat(s.getNumberOfPages()).isEqualTo(o.getNumberOfPages() + 1);
            COSArray oid = o.getDocument().getTrailer().getCOSArray(COSName.ID);
            COSArray sid = s.getDocument().getTrailer().getCOSArray(COSName.ID);
            assertThat(((COSString) sid.get(0)).getBytes()).as("first /ID element is the sealed one").isEqualTo(((COSString) oid.get(0)).getBytes());
            assertThat(((COSString) sid.get(1)).getBytes()).isNotEqualTo(((COSString) oid.get(1)).getBytes());
            Instant last = g.signatures().stream().map(SignatureAppearance::signedAt).max(Instant::compareTo).orElseThrow();
            assertThat(s.getDocumentInformation().getModificationDate().toInstant()).isEqualTo(last);
            assertThat(s.getDocumentInformation().getCreationDate().toInstant()).isEqualTo(o.getDocumentInformation().getCreationDate().toInstant());
            String xmp = new String(s.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertThat(xmp).contains("<xmp:ModifyDate>2026-09-24T14:40:00+09:00</xmp:ModifyDate>")
                    .contains("<xmp:MetadataDate>2026-09-24T14:40:00+09:00</xmp:MetadataDate>")
                    .contains("<xmp:CreateDate>2026-09-23T00:00:00+09:00</xmp:CreateDate>")
                    .contains("<pdfaid:part>2</pdfaid:part>");
        }
    }

    /** /ID 둘째 원소의 시드는 서명 요약에서 온다 — 서명 하나만 바뀌어도 다르고, 같은 요약이면 같다(시각 기반이 아니다). */
    @Test
    void documentIdSeedFollowsTheSignatureSummary() throws Exception {
        SignedGolden g = SignedGolden.of("signed-01");
        byte[] original = g.originalPdf();
        List<SignatureAppearance> changed = new ArrayList<>(g.signatures());
        SignatureAppearance m = changed.removeLast();
        changed.add(new SignatureAppearance(m.role(), m.channel(), m.method(), m.signedAt().plusSeconds(1), m.identity(), m.imagePng()));
        byte[] a = g.sign(original, g.signatures()).pdf();
        byte[] b = g.sign(original, changed).pdf();
        try (PDDocument da = Loader.loadPDF(a); PDDocument db = Loader.loadPDF(b)) {
            byte[] ida = ((COSString) da.getDocument().getTrailer().getCOSArray(COSName.ID).get(1)).getBytes();
            byte[] idb = ((COSString) db.getDocument().getTrailer().getCOSArray(COSName.ID).get(1)).getBytes();
            assertThat(ida).isNotEqualTo(idb);
        }
    }

    @Test
    void noSignaturesNoSignedPdf() {
        SignedGolden g = SignedGolden.of("signed-01");
        assertThatThrownBy(() -> g.sign(g.originalPdf(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
