package com.ga.disclosure.seal.renderer;

import com.ga.disclosure.rules.template.SignaturePageLayout;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.seal.canonical.CanonicalView;
import com.ga.platform.canonical.Canonicalizer;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SimpleTimeZone;

/**
 * 서명본 PDF(3B 수용심사 §3-2, 4 계획 §3): 봉인 PDF 원본 바이트 뒤에 서명 외관 페이지를 <b>증분 갱신</b>으로 붙인다 — 원본은 서명본의
 * 바이트 접두이고, 원본 해시({@code pdf_hash})로 서명본 안의 원본을 언제나 다시 확인할 수 있다. 암호학적 PAdES 서명이 아니다(증거력은
 * 체인·증거 패키지에 있다, 설계서 §6.5). 결정론(렌더러와 같은 환경 무의존):
 * <ol>
 *   <li>서명 페이지는 봉인 PDF와 같은 구성(PDF/A-2b, 동봉 폰트·ICC)으로 만들고 <b>한 번 저장해 다시 읽은 뒤</b> 폼 XObject로 복제한다 —
 *       폰트 서브셋은 저장 때 확정되므로, 저장 전 복제는 폰트가 임베드되지 않는다(veraPDF 6.2.11.4.1, 스파이크).</li>
 *   <li>정보 {@code /ModDate}와 XMP {@code ModifyDate}·{@code MetadataDate} = 마지막 서명 시각(초 단위, 고정 오프셋 +09:00). XMP는 고정
 *       템플릿으로 통째 다시 쓴다(만든 시각 = 상담일 00:00 +09:00 그대로).</li>
 *   <li>트레일러 {@code /ID}: 첫 원소는 원본 그대로, 둘째 원소는 PDFBox가 증분 저장 때 문서 ID 시드로 만든다 — 시드 = SHA-256(ASCII(pdf_hash) ‖
 *       JCS(서명 요약))의 앞 8바이트. 시드가 없으면 시각 기반이라 매번 바뀐다(스파이크).</li>
 * </ol>
 */
public final class SignedPdfAppender {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    /**
     * @param version     문서에 고정된 렌더러 판(Phase 8 — 서명 외관 페이지·갱신분 XMP의 생산자가 봉인 PDF와 같다)
     * @param originalPdf 봉인 PDF 원본 바이트(SHA-256 = {@code pdf_hash})
     * @param signatures  서명 순서대로(비어 있으면 안 된다)
     */
    public DisclosurePdfRenderer.Rendered append(RendererVersion version, byte[] originalPdf, CanonicalDocument canonical, TemplateResolution template,
                                                 String disclosureNo, List<SignatureAppearance> signatures) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(originalPdf, "originalPdf");
        if (signatures.isEmpty()) {
            throw new IllegalArgumentException("a signed PDF needs at least one signature");
        }
        String pdfHash = DisclosurePdfRenderer.hex(DisclosurePdfRenderer.sha256(originalPdf));
        SignaturePageLayout labels = SignaturePageLayout.of(template);
        Instant last = signatures.stream().map(SignatureAppearance::signedAt).max(Instant::compareTo).orElseThrow();
        OffsetDateTime modified = last.atOffset(KST).truncatedTo(ChronoUnit.SECONDS);
        try {
            byte[] pageBytes = signaturePage(version, labels, disclosureNo, canonical.sha256().substring(0, 12), pdfHash, signatures);
            try (PDDocument doc = Loader.loadPDF(originalPdf); PDDocument page = Loader.loadPDF(pageBytes)) {
                LayerUtility layers = new LayerUtility(doc);
                for (int i = 0; i < page.getNumberOfPages(); i++) {
                    PDFormXObject form = layers.importPageAsForm(page, i);
                    PDPage target = new PDPage(page.getPage(i).getMediaBox());
                    try (PDPageContentStream cs = new PDPageContentStream(doc, target)) {
                        cs.drawForm(form);
                    }
                    doc.addPage(target);
                }
                PDDocumentInformation info = doc.getDocumentInformation();
                info.setModificationDate(calendar(modified));
                String created = DisclosurePdfRenderer.xmpDate(new CanonicalView(canonical, disclosureNo).consultDate());
                PDMetadata metadata = new PDMetadata(doc, new ByteArrayInputStream(DisclosurePdfRenderer.xmp(version, template.title(), created,
                        xmpDate(modified)).getBytes(StandardCharsets.UTF_8)));
                doc.getDocumentCatalog().setMetadata(metadata);
                doc.setDocumentId(seed(pdfHash, signatures));
                doc.getDocumentCatalog().getCOSObject().setNeedToBeUpdated(true);
                doc.getPages().getCOSObject().setNeedToBeUpdated(true);
                info.getCOSObject().setNeedToBeUpdated(true);
                ByteArrayOutputStream out = new ByteArrayOutputStream(originalPdf.length + pageBytes.length);
                doc.saveIncremental(out);                          // 원본 바이트 + 갱신분
                byte[] signed = out.toByteArray();
                if (signed.length <= originalPdf.length || !Arrays.equals(signed, 0, originalPdf.length, originalPdf, 0, originalPdf.length)) {
                    throw new IllegalStateException("incremental save did not keep the sealed PDF as a byte prefix");
                }
                return new DisclosurePdfRenderer.Rendered(signed, DisclosurePdfRenderer.hex(DisclosurePdfRenderer.sha256(signed)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 서명 외관 페이지를 봉인 PDF와 같은 구성으로 렌더해 저장한 바이트(폰트 서브셋 확정). 이 문서의 정보·XMP·/ID는 복제되지 않는다. */
    private static byte[] signaturePage(RendererVersion version, SignaturePageLayout labels, String disclosureNo, String hash12, String pdfHash,
                                        List<SignatureAppearance> signatures) throws IOException {
        String html = SignaturePageComposer.compose(labels, disclosureNo, hash12, pdfHash, signatures);
        PdfRendererBuilder b = new PdfRendererBuilder();
        b.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_2_B);
        b.useColorProfile(RenderAssets.SRGB);
        b.useFont(() -> new ByteArrayInputStream(RenderAssets.REGULAR), RenderAssets.FONT_FAMILY, 400, FontStyle.NORMAL, true);
        b.useFont(() -> new ByteArrayInputStream(RenderAssets.BOLD), RenderAssets.FONT_FAMILY, 700, FontStyle.NORMAL, true);
        b.withHtmlContent(html, null);
        b.withProducer(version.producer());
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        b.toStream(out);
        try (PdfBoxRenderer r = b.buildPdfRenderer()) {
            r.createPDFWithoutClosing();
            r.getPdfDocument().save(out);
        }
        return out.toByteArray();
    }

    /** 문서 ID 시드: SHA-256(ASCII(pdf_hash) ‖ JCS(서명 요약))의 앞 8바이트(빅엔디언 long). 요약에는 이미지 대신 이미지 해시만. */
    static long seed(String pdfHash, List<SignatureAppearance> signatures) {
        ArrayNode summary = JsonNodeFactory.instance.arrayNode();
        for (SignatureAppearance s : signatures) {
            ObjectNode o = summary.addObject();
            o.put("role", s.role().name());
            o.put("channel", s.channel().name());
            o.put("method", s.method().name());
            o.put("signedAt", s.signedAt().toString());
            ArrayNode identity = o.putArray("identity");
            s.identity().forEach(r -> identity.addObject().put("method", r.method().name()).put("passed", r.passed()));
            byte[] image = s.imagePng();
            if (image == null) {
                o.putNull("imageSha256");
            } else {
                o.put("imageSha256", DisclosurePdfRenderer.hex(DisclosurePdfRenderer.sha256(image)));
            }
        }
        byte[] json = Canonicalizer.canonicalize(summary);
        byte[] prefix = pdfHash.getBytes(StandardCharsets.US_ASCII);
        byte[] input = Arrays.copyOf(prefix, prefix.length + json.length);
        System.arraycopy(json, 0, input, prefix.length, json.length);
        return ByteBuffer.wrap(DisclosurePdfRenderer.sha256(input), 0, 8).getLong();
    }

    static String xmpDate(OffsetDateTime t) {
        return t.toLocalDate() + "T" + two(t.getHour()) + ":" + two(t.getMinute()) + ":" + two(t.getSecond()) + "+09:00";
    }

    /** 고정 오프셋 +09:00 달력(시간대 DB·기본 로케일을 읽지 않는다). */
    private static Calendar calendar(OffsetDateTime t) {
        Calendar c = new GregorianCalendar(new SimpleTimeZone(9 * 60 * 60 * 1000, "KST"), Locale.ROOT);
        c.clear();
        c.set(t.getYear(), t.getMonthValue() - 1, t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond());
        return c;
    }

    static String two(int v) {
        return v < 10 ? "0" + v : Integer.toString(v);
    }

    /** 서명 PNG를 data URI로(파일·네트워크 접근 없음). */
    static String dataUri(byte[] png) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }
}
