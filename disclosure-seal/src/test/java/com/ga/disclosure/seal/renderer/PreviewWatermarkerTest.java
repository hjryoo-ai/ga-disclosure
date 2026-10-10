package com.ga.disclosure.seal.renderer;

import com.ga.disclosure.seal.canonical.SealFixtures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 7 ⑥: 미리보기 워터마크는 모든 쪽에 문구를 얹은 새 바이트를 만들고 입력(봉인 PDF) 배열은 바꾸지 않는다. 쪽 수·쪽 크기는 원본과 같고 폰트는 임베드된다.
 * 문구는 시험 값(룰 데이터가 아닌 임의 문자열)이다 — 워터마커에 문구 리터럴이 없다.
 */
class PreviewWatermarkerTest {

    @Test
    void everyPageCarriesTheMarkAndTheInputBytesAreUntouched() throws Exception {
        byte[] sealed = new DisclosurePdfRenderer().render(RendererVersion.CURRENT, RenderFixtures.canonical(SealFixtures.case02()), RenderFixtures.STANDARD,
                RenderFixtures.number(7)).pdf();
        byte[] copy = sealed.clone();
        String mark = "PREVIEW-MARK <&> 2026-10-10 09:30";
        byte[] preview = new PreviewWatermarker().watermark(sealed, mark);

        assertThat(sealed).isEqualTo(copy);
        assertThat(preview).isNotEqualTo(sealed);
        try (PDDocument original = Loader.loadPDF(sealed); PDDocument marked = Loader.loadPDF(preview)) {
            assertThat(marked.getNumberOfPages()).isEqualTo(original.getNumberOfPages()).isGreaterThan(0);
            for (int i = 0; i < marked.getNumberOfPages(); i++) {
                assertThat(Arrays.toString(marked.getPage(i).getMediaBox().getCOSArray().toFloatArray()))
                        .isEqualTo(Arrays.toString(original.getPage(i).getMediaBox().getCOSArray().toFloatArray()));
                // 회전한 글자는 추출 때 줄이 갈린다 — 공백·줄바꿈을 지우고 센다(쪽마다 띠 수만큼)
                PDFTextStripper page = new PDFTextStripper();
                page.setStartPage(i + 1);
                page.setEndPage(i + 1);
                String text = page.getText(marked).replaceAll("\\s", "");
                String needle = mark.replaceAll("\\s", "");
                assertThat(text.split(java.util.regex.Pattern.quote(needle), -1)).as("page " + (i + 1)).hasSize(PreviewWatermarker.BANDS + 1);
                assertThat(new PDFTextStripper().getText(original)).doesNotContain(mark);
            }
            for (PDPage page : marked.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    assertThat(font.isEmbedded()).as(font.getName()).isTrue();
                }
            }
        }
    }

    @Test
    void aBlankMarkIsRefused() {
        assertThatThrownBy(() -> new PreviewWatermarker().watermark(new byte[] {1}, " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
