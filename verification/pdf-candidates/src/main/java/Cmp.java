import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.TimeZone;

/**
 * PDF/A 변환기 후보 비교(3A 선행 소과제 D): 같은 XHTML을 후보마다 변환해 PDF/A 준수(veraPDF)·한글 폰트 임베드·결정론(별도 프로세스 2회 → 바이트
 * 비교)을 잰다. 폰트·sRGB ICC는 저장소 동봉 자산만 쓴다(JDK 내장 ICC를 쓰지 않는다 — JDK·OS 차이 제거). 봉인 렌더러가 아니다(3B).
 */
public class Cmp {

    static final String BASE = new File(".").toURI().toString();
    static final Path RENDER = Path.of("../../disclosure-seal/src/main/resources/render");

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "ohtp-naive" -> ohtp(Path.of(a[1]), false);
            case "ohtp" -> ohtp(Path.of(a[1]), true);
            case "fs-naive" -> fs(Path.of(a[1]), false);
            case "fs" -> fs(Path.of(a[1]), true);
            case "verify" -> verify(Path.of(a[1]), a.length > 2 ? a[2] : null);
            case "dump" -> dump(Path.of(a[1]));
            case "info" -> info(Path.of(a[1]));
            case "diff" -> diff(Path.of(a[1]), Path.of(a[2]));
            default -> throw new IllegalArgumentException(a[0]);
        }
    }

    static String html() throws Exception {
        return Files.readString(Path.of("sample.html"));
    }

    static Calendar fixed() {
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 23, 1, 15, 30);
        return c;
    }

    // ---------------------------------------------------------------- openhtmltopdf (PDFBox 3)
    static void ohtp(Path out, boolean pin) throws Exception {
        PdfRendererBuilder b = new PdfRendererBuilder();
        b.useFastMode();
        b.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_2_B);
        b.useColorProfile(Files.readAllBytes(RENDER.resolve("icc/sRGB-v2-magic.icc")));
        b.useFont(RENDER.resolve("fonts/NanumGothic-Regular.ttf").toFile(), "Nanum", 400, FontStyle.NORMAL, true);
        b.useFont(RENDER.resolve("fonts/NanumGothic-Bold.ttf").toFile(), "Nanum", 700, FontStyle.NORMAL, true);
        b.withHtmlContent(html(), BASE);
        if (pin) {
            b.withProducer("ga-disclosure-renderer");
        }
        try (OutputStream os = Files.newOutputStream(out)) {
            b.toStream(os);
            if (!pin) {
                b.run();
                return;
            }
            try (PdfBoxRenderer r = b.buildPdfRenderer()) {
                r.createPDFWithoutClosing();
                PDDocument doc = r.getPdfDocument();
                PDDocumentInformation info = doc.getDocumentInformation();
                info.setCreationDate(fixed());
                info.setModificationDate(fixed());
                PDMetadata md = doc.getDocumentCatalog().getMetadata();
                if (md != null) {
                    String xmp = new String(md.toByteArray(), StandardCharsets.UTF_8);
                    xmp = xmp.replaceAll("(<xmp:(CreateDate|ModifyDate|MetadataDate)>)[^<]*(</xmp:\\2>)", "$12026-09-23T01:15:30Z$3");
                    xmp = xmp.replaceAll("(xmp:(CreateDate|ModifyDate|MetadataDate)=\")[^\"]*(\")", "$12026-09-23T01:15:30Z$3");
                    PDMetadata fixedMd = new PDMetadata(doc, new java.io.ByteArrayInputStream(xmp.getBytes(StandardCharsets.UTF_8)));
                    doc.getDocumentCatalog().setMetadata(fixedMd);
                }
                doc.setDocumentId(0x5EED_0481L);
                doc.save(os);
            }
        }
    }

    // ---------------------------------------------------------------- Flying Saucer (OpenPDF)
    static void fs(Path out, boolean pin) throws Exception {
        org.xhtmlrenderer.pdf.ITextRenderer r = new org.xhtmlrenderer.pdf.ITextRenderer();
        r.getFontResolver().addFont(RENDER.resolve("fonts/NanumGothic-Regular.ttf").toString(), "Identity-H", true);
        r.getFontResolver().addFont(RENDER.resolve("fonts/NanumGothic-Bold.ttf").toString(), "Identity-H", true);
        r.setPDFXConformance(org.openpdf.text.pdf.PdfWriter.PDFA2B);
        if (pin) {
            r.setListener(new org.xhtmlrenderer.pdf.DefaultPDFCreationListener() {
                @Override
                public void preOpen(org.xhtmlrenderer.pdf.ITextRenderer renderer) {
                    var w = renderer.getWriter();
                    w.getInfo().put(org.openpdf.text.pdf.PdfName.CREATIONDATE, new org.openpdf.text.pdf.PdfDate(fixed()));
                    w.getInfo().put(org.openpdf.text.pdf.PdfName.MODDATE, new org.openpdf.text.pdf.PdfDate(fixed()));
                }
            });
        }
        r.setDocumentFromString(html(), BASE);
        r.layout();
        try (OutputStream os = Files.newOutputStream(out)) {
            r.createPDF(os);
        }
    }

    // ---------------------------------------------------------------- veraPDF
    static void verify(Path pdf, String forced) throws Exception {
        org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider.initialise();
        try (var in = new FileInputStream(pdf.toFile());
             var parser = org.verapdf.pdfa.Foundries.defaultInstance().createParser(in)) {
            var flavour = forced != null ? org.verapdf.pdfa.flavours.PDFAFlavour.fromString(forced) : parser.getFlavour();
            try (var validator = org.verapdf.pdfa.Foundries.defaultInstance().createValidator(flavour, false)) {
                var result = validator.validate(parser);
                System.out.println("veraPDF flavour=" + flavour + " compliant=" + result.isCompliant()
                        + " failedChecks=" + result.getFailedChecks().size());
                result.getFailedChecks().keySet().stream().limit(8).forEach(rule -> System.out.println("  fail " + rule.getClause() + " #" + rule.getTestNumber()));
            }
        }
    }

    static void dump(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            System.out.println("ID=" + doc.getDocument().getTrailer().getDictionaryObject(org.apache.pdfbox.cos.COSName.ID));
            System.out.println("Info=" + doc.getDocumentInformation().getCOSObject());
            PDMetadata md = doc.getDocumentCatalog().getMetadata();
            System.out.println("XMP=" + (md == null ? null : new String(md.toByteArray(), StandardCharsets.UTF_8)));
        }
    }

    static void info(Path pdf) throws Exception {
        byte[] bytes = Files.readAllBytes(pdf);
        System.out.println(pdf.getFileName() + " bytes=" + bytes.length + " sha256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            System.out.println("  version=" + doc.getVersion() + " pages=" + doc.getNumberOfPages()
                    + " producer=" + doc.getDocumentInformation().getProducer());
            for (PDPage p : doc.getPages()) {
                for (var name : p.getResources().getFontNames()) {
                    PDFont f = p.getResources().getFont(name);
                    System.out.println("  font " + f.getName() + " embedded=" + f.isEmbedded() + " type=" + f.getSubType());
                }
            }
            String text = new PDFTextStripper().getText(doc);
            System.out.println("  text has 한글: " + text.contains("보험상품 비교설명 확인서") + ", 똠방각하: " + text.contains("똠방각하") + ", 뷁: " + text.contains("뷁"));
        }
    }

    static void diff(Path x, Path y) throws Exception {
        byte[] a = Files.readAllBytes(x), b = Files.readAllBytes(y);
        int n = Math.min(a.length, b.length), diffs = 0, first = -1;
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) {
                diffs++;
                if (first < 0) first = i;
            }
        }
        System.out.println("identical=" + java.util.Arrays.equals(a, b) + " len " + a.length + "/" + b.length + " differingBytes=" + diffs + " first=" + first);
        if (first >= 0) {
            int s = Math.max(0, first - 60);
            System.out.println("  A: " + printable(a, s, first + 60));
            System.out.println("  B: " + printable(b, s, first + 60));
        }
    }

    static String printable(byte[] b, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < Math.min(to, b.length); i++) {
            int c = b[i] & 0xff;
            sb.append(c >= 32 && c < 127 ? (char) c : '.');
        }
        return sb.toString();
    }
}
