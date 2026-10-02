package com.ga.disclosure.seal.renderer;

import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.seal.canonical.CanonicalView;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDMetadata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.Objects;
import java.util.SimpleTimeZone;

/**
 * 확인서 PDF/A-2b 렌더러(openhtmltopdf + PDFBox, 3B 계획 §5). 입력은 봉인 본문 + 고정 서식 + 확인서 번호뿐이고, 벽시계·{@code Clock}·난수·
 * 환경(기본 로케일·시간대·인코딩)을 읽지 않는다 — 같은 입력이면 어느 JVM·OS에서든 같은 바이트다. 라이브러리가 매번 바꾸는 세 지점은 입력에서
 * 파생해 고정한다.
 * <ol>
 *   <li>문서 정보 {@code /CreationDate}·{@code /ModDate}: 상담일 00:00, 고정 오프셋 +09:00(시간대 DB를 읽지 않는 {@link SimpleTimeZone}).</li>
 *   <li>XMP: 라이브러리 산출물을 고치지 않고 통째로 새로 쓴다 — {@code pdfaid} 2/B, {@code dc:title} = 서식 제목(= 정보 {@code /Title}),
 *       {@code pdf:Producer} = {@value #PRODUCER}(= 정보 {@code /Producer}), 세 날짜 = 상담일 {@code T00:00:00+09:00}.</li>
 *   <li>트레일러 {@code /ID}: {@code h = SHA-256(ASCII(canonical_hash) ‖ ASCII(disclosure_no))}, 두 원소 모두 {@code h[0..16)}.</li>
 * </ol>
 */
public final class DisclosurePdfRenderer {

    public static final String PRODUCER = "ga-disclosure-renderer/1";
    private static final int KST_OFFSET_MILLIS = 9 * 60 * 60 * 1000;

    /** 렌더 결과: PDF 바이트와 그 SHA-256({@code pdf_hash}). */
    public record Rendered(byte[] pdf, String sha256) {
        public Rendered {
            pdf = pdf.clone();
        }

        @Override
        public byte[] pdf() {
            return pdf.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Rendered r && Arrays.equals(pdf, r.pdf);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(pdf);
        }

        @Override
        public String toString() {
            return "Rendered[" + pdf.length + " bytes, " + sha256 + "]";
        }
    }

    public Rendered render(CanonicalDocument canonical, TemplateResolution template, String disclosureNo) {
        Objects.requireNonNull(canonical, "canonical");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(disclosureNo, "disclosureNo");
        CanonicalView view = new CanonicalView(canonical, disclosureNo);
        String html = HtmlComposer.compose(template, view, disclosureNo, canonical.sha256().substring(0, 12));
        LocalDate consult = view.consultDate();
        try {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_2_B);
            b.useColorProfile(RenderAssets.SRGB);
            b.useFont(() -> new ByteArrayInputStream(RenderAssets.REGULAR), RenderAssets.FONT_FAMILY, 400, FontStyle.NORMAL, true);
            b.useFont(() -> new ByteArrayInputStream(RenderAssets.BOLD), RenderAssets.FONT_FAMILY, 700, FontStyle.NORMAL, true);
            b.withHtmlContent(html, null);
            b.withProducer(PRODUCER);
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            b.toStream(out);
            try (PdfBoxRenderer r = b.buildPdfRenderer()) {
                r.createPDFWithoutClosing();
                PDDocument doc = r.getPdfDocument();
                pin(doc, template.title(), consult, documentId(canonical.sha256(), disclosureNo));
                doc.save(out);
            }
            byte[] pdf = out.toByteArray();
            return new Rendered(pdf, hex(sha256(pdf)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 세 지점 고정(클래스 주석). 정보 사전은 새로 만들어 라이브러리가 넣은 다른 항목이 남지 않게 한다. */
    static void pin(PDDocument doc, String title, LocalDate consult, byte[] id) throws IOException {
        Calendar at = consultMidnightKst(consult);
        PDDocumentInformation info = new PDDocumentInformation();
        info.setTitle(title);
        info.setProducer(PRODUCER);
        info.setCreationDate(at);
        info.setModificationDate(at);
        doc.setDocumentInformation(info);
        PDMetadata metadata = new PDMetadata(doc, new ByteArrayInputStream(xmp(title, consult).getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentCatalog().setMetadata(metadata);
        COSArray ids = new COSArray();
        ids.add(new COSString(id));
        ids.add(new COSString(id));
        doc.getDocument().getTrailer().setItem(COSName.ID, ids);
    }

    /** 상담일 00:00 +09:00. 고정 오프셋 객체·{@link Locale#ROOT} — 시스템 시간대·로케일을 읽지 않는다. */
    static Calendar consultMidnightKst(LocalDate consult) {
        Calendar c = new GregorianCalendar(new SimpleTimeZone(KST_OFFSET_MILLIS, "KST"), Locale.ROOT);
        c.clear();
        c.set(consult.getYear(), consult.getMonthValue() - 1, consult.getDayOfMonth(), 0, 0, 0);
        return c;
    }

    /** {@code /ID} 원소: SHA-256(ASCII(canonical_hash) ‖ ASCII(disclosure_no))의 앞 16바이트. */
    static byte[] documentId(String canonicalHash, String disclosureNo) {
        byte[] h = sha256((canonicalHash + disclosureNo).getBytes(StandardCharsets.US_ASCII));
        return Arrays.copyOf(h, 16);
    }

    static String xmpDate(LocalDate consult) {
        return consult + "T00:00:00+09:00";
    }

    /** PDF/A-2b 식별·정보 사전과 일치하는 XMP(고정 템플릿). */
    static String xmp(String title, LocalDate consult) {
        String date = xmpDate(consult);
        return xmp(title, date, date);
    }

    /** 같은 고정 템플릿, 만든 시각과 고친 시각을 따로(서명본 증분 갱신 — 고친 시각 = 마지막 서명 시각). */
    static String xmp(String title, String createDate, String modifyDate) {
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\">\n"
                + "<pdfaid:part>2</pdfaid:part>\n<pdfaid:conformance>B</pdfaid:conformance>\n</rdf:Description>\n"
                + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "<dc:format>application/pdf</dc:format>\n"
                + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">" + HtmlComposer.esc(title) + "</rdf:li></rdf:Alt></dc:title>\n"
                + "</rdf:Description>\n"
                + "<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">\n"
                + "<xmp:CreateDate>" + createDate + "</xmp:CreateDate>\n<xmp:ModifyDate>" + modifyDate + "</xmp:ModifyDate>\n"
                + "<xmp:MetadataDate>" + modifyDate + "</xmp:MetadataDate>\n</rdf:Description>\n"
                + "<rdf:Description rdf:about=\"\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">\n"
                + "<pdf:Producer>" + PRODUCER + "</pdf:Producer>\n</rdf:Description>\n"
                + "</rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>";
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
