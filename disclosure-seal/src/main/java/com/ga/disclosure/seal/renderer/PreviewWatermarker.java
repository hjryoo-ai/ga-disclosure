package com.ga.disclosure.seal.renderer;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * 화면 미리보기 워터마크(Phase 7 계획 ⑥, 지시문 §4): 봉인 PDF의 <b>모든 쪽</b>에 대각선 반투명 문구를 덧그린 <b>새</b> 바이트를 만든다. 입력 배열은 읽기만
 * 하고, 결과는 저장하지 않는다 — 호출자가 응답으로만 흘린다(산출물·증거 패키지·공개 서명 PDF는 이 경로를 지나지 않는다). PDFBox의 부동소수 좌표 API를 쓰지
 * 않으려고 서명본({@link SignedPdfAppender})과 같은 방식으로 워터마크 쪽을 openhtmltopdf로 렌더해 저장·재적재한 뒤 폼 XObject로 각 쪽 위에 그린다(봉인 PDF는
 * 렌더러가 만든 A4 세로라 같은 크기). 폰트는 렌더러와 같은 나눔고딕. PDF/A·결정론 요구 없음(문구에 열람 시각이 들어간다). 문구는 호출자가 룰 데이터에서
 * 만든다 — 이 클래스에 문구 리터럴이 없다.
 */
public final class PreviewWatermarker {

    /** 쪽마다 대각선 문구를 위에서 아래로 몇 줄 둘지(가로 여백을 덮도록). */
    static final int BANDS = 3;

    public byte[] watermark(byte[] sealedPdf, String text) {
        Objects.requireNonNull(sealedPdf, "sealedPdf");
        if (Objects.requireNonNull(text, "text").isBlank()) {
            throw new IllegalArgumentException("watermark text is blank");
        }
        try {
            byte[] markPage = markPage(text);
            try (PDDocument doc = Loader.loadPDF(sealedPdf.clone()); PDDocument mark = Loader.loadPDF(markPage)) {
                PDFormXObject form = new LayerUtility(doc).importPageAsForm(mark, 0);
                for (PDPage page : doc.getPages()) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        cs.drawForm(form);
                    }
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(sealedPdf.length + markPage.length);
                doc.save(out);
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A4 한 쪽: 대각선 반투명 문구 {@value #BANDS}줄. 저장한 바이트(폰트 서브셋 확정 — 저장 전 복제는 폰트가 임베드되지 않는다). */
    static byte[] markPage(String text) throws IOException {
        StringBuilder html = new StringBuilder(1024);
        html.append("<html><head><style>@page { size: A4 portrait; margin: 0; } body { margin: 0; font-family: '")
                .append(RenderAssets.FONT_FAMILY).append("'; }")
                .append(" div.mark { position: absolute; left: -25mm; width: 260mm; text-align: center; font-size: 24pt; font-weight: 700;")
                .append(" color: #808080; opacity: 0.28; transform: rotate(-35deg); }</style></head><body>");
        for (int i = 0; i < BANDS; i++) {
            html.append("<div class=\"mark\" style=\"top: ").append(45 + i * 95).append("mm;\">").append(HtmlComposer.esc(text)).append("</div>");
        }
        html.append("</body></html>");
        PdfRendererBuilder b = new PdfRendererBuilder();
        b.useFont(() -> new ByteArrayInputStream(RenderAssets.BOLD), RenderAssets.FONT_FAMILY, 700, FontStyle.NORMAL, true);
        b.withHtmlContent(html.toString(), null);
        // 워터마크 쪽 문서의 정보 사전은 폼으로 옮겨지지 않는다(봉인 PDF의 정보·XMP가 그대로 남는다) — 생산자 값은 결과에 닿지 않아 판과 무관하다
        b.withProducer(RendererVersion.V1.producer());
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        try (PdfBoxRenderer r = b.buildPdfRenderer()) {
            r.createPDFWithoutClosing();
            r.getPdfDocument().save(out);
        }
        return out.toByteArray();
    }
}
