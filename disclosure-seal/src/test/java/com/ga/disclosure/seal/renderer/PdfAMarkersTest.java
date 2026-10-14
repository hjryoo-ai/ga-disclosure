package com.ga.disclosure.seal.renderer;

import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.seal.canonical.SealFixtures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B S12(메인 테스트 몫): PDF/A-2b 구조 마커 — XMP {@code pdfaid:part=2}·{@code conformance=B}, OutputIntent {@code GTS_PDFA1} + ICC, 폰트 전부 임베드.
 * 준수 판정 자체는 CI {@code pdfa-verify}(veraPDF)가 한다. 세 지점 고정의 실제 값(상담일 00:00 +09:00, XMP 일치, {@code /ID} 파생식)과
 * 각주 텍스트도 여기서 본다.
 */
class PdfAMarkersTest {

    private static final DisclosurePdfRenderer RENDERER = new DisclosurePdfRenderer();

    @Test
    void structuralMarkersAndPinnedPoints() throws Exception {
        CanonicalDocument canonical = RenderFixtures.canonical(SealFixtures.case02());
        String no = RenderFixtures.number(481);
        byte[] pdf = RENDERER.render(RendererVersion.CURRENT, canonical, RenderFixtures.STANDARD, no).pdf();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertThat(xmp).contains("<pdfaid:part>2</pdfaid:part>", "<pdfaid:conformance>B</pdfaid:conformance>",
                    "<xmp:CreateDate>2026-09-23T00:00:00+09:00</xmp:CreateDate>", "<xmp:ModifyDate>2026-09-23T00:00:00+09:00</xmp:ModifyDate>",
                    "<pdf:Producer>" + RendererVersion.CURRENT.producer() + "</pdf:Producer>", RenderFixtures.STANDARD.title());
            List<PDOutputIntent> intents = doc.getDocumentCatalog().getOutputIntents();
            assertThat(intents).hasSize(1);
            assertThat(intents.getFirst().getCOSObject().getNameAsString(COSName.S)).isEqualTo("GTS_PDFA1");
            assertThat(intents.getFirst().getDestOutputIntent()).isNotNull();
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    assertThat(font.isEmbedded()).as(font.getName()).isTrue();
                }
            }
            var info = doc.getDocumentInformation();
            assertThat(info.getTitle()).isEqualTo(RenderFixtures.STANDARD.title());
            assertThat(info.getProducer()).isEqualTo(RendererVersion.CURRENT.producer());
            assertThat(info.getCOSObject().getString(COSName.CREATION_DATE)).isEqualTo("D:20260923000000+09'00'");
            assertThat(info.getCOSObject().getString(COSName.MOD_DATE)).isEqualTo("D:20260923000000+09'00'");
            assertThat(info.getCOSObject().keySet()).containsExactlyInAnyOrder(COSName.TITLE, COSName.PRODUCER, COSName.CREATION_DATE,
                    COSName.MOD_DATE);
            COSArray id = doc.getDocument().getTrailer().getCOSArray(COSName.ID);
            byte[] expected = DisclosurePdfRenderer.documentId(canonical.sha256(), no);
            assertThat(((COSString) id.get(0)).getBytes()).isEqualTo(expected);
            assertThat(((COSString) id.get(1)).getBytes()).isEqualTo(expected);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains(no, canonical.sha256().substring(0, 12), "1/" + doc.getNumberOfPages());
            assertThat(text).contains("가상고객").as("성명은 인쇄되는 유일한 개인정보").doesNotContain(SealFixtures.case02().customerRef().value());
            assertThat(text).doesNotContain("0.84").as("비율은 인쇄하지 않는다(설계서 §6.3)");
        }
        assertThat(HexFormat.of().formatHex(DisclosurePdfRenderer.documentId(canonical.sha256(), no))).hasSize(32);
    }

    @Test
    void sameInputSameBytesEvenUnderAnotherDefaultLocaleAndTimeZone() {
        CanonicalDocument canonical = RenderFixtures.canonical(SealFixtures.case01());
        byte[] first = RENDERER.render(RendererVersion.CURRENT, canonical, RenderFixtures.STANDARD, RenderFixtures.number(1)).pdf();
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.US);
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            assertThat(RENDERER.render(RendererVersion.CURRENT, canonical, RenderFixtures.STANDARD, RenderFixtures.number(1)).pdf()).isEqualTo(first);
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
        }
        assertThat(RENDERER.render(RendererVersion.CURRENT, canonical, RenderFixtures.STANDARD, RenderFixtures.number(2)).pdf()).as("번호가 바뀌면 바이트도").isNotEqualTo(first);
    }

    @Test
    void fiftyItemsSpanSeveralPages() throws Exception {
        byte[] pdf = RENDERER.render(RendererVersion.CURRENT, RenderFixtures.canonical(SealFixtures.case03()), RenderFixtures.STANDARD, RenderFixtures.number(3)).pdf();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("50", doc.getNumberOfPages() + "/" + doc.getNumberOfPages());
        }
    }
}
