package com.ga.disclosure.seal.renderer;

import com.ga.disclosure.rules.template.SignaturePageLayout;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 서명 외관 페이지 XHTML(설계서 §6.5 v1.9): 제목, 서명 표(역할·경로·방법·서명 시각·본인확인 결과·서명 이미지), 원본 확인서 PDF의 SHA-256.
 * 문구는 전부 서식 {@code layout.signaturePage} 데이터이고 이 클래스에는 문구 리터럴이 없다. 시각은 고정 오프셋 +09:00 초 단위 원문 표기.
 * 각주는 확인서 번호 · canonical 해시 앞 12자(봉인 PDF 각주와 같은 연결).
 */
final class SignaturePageComposer {

    private static final Pattern FOOTER_SAFE = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private SignaturePageComposer() {
    }

    static String compose(SignaturePageLayout labels, String disclosureNo, String hash12, String pdfHash, List<SignatureAppearance> signatures) {
        if (!FOOTER_SAFE.matcher(disclosureNo).matches() || !FOOTER_SAFE.matcher(hash12).matches() || !HEX64.matcher(pdfHash).matches()) {
            throw new IllegalArgumentException("footer and hash values must be plain identifiers");
        }
        String footer = disclosureNo + " · " + hash12;
        StringBuilder h = new StringBuilder(8_192);
        h.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"UTF-8\"/><title>").append(HtmlComposer.esc(labels.title()))
                .append("</title><style>").append(css(footer)).append("</style></head><body>");
        h.append("<h1>").append(HtmlComposer.esc(labels.title())).append("</h1>");
        SignaturePageLayout.Columns c = labels.columns();
        h.append("<table class=\"sig\"><colgroup>");
        for (int width : COLUMN_PERCENT) {
            h.append("<col style=\"width: ").append(width).append("%\"/>");
        }
        h.append("</colgroup><tr>");
        for (String head : List.of(c.role(), c.channel(), c.method(), c.signedAt(), c.identity(), c.image())) {
            h.append("<th>").append(HtmlComposer.esc(head)).append("</th>");
        }
        h.append("</tr>");
        for (SignatureAppearance s : signatures) {
            h.append("<tr><td>").append(HtmlComposer.esc(labels.role(s.role()))).append("</td>")
                    .append("<td>").append(HtmlComposer.esc(labels.channel(s.channel()))).append("</td>")
                    .append("<td>").append(HtmlComposer.esc(labels.method(s.method()))).append("</td>")
                    .append("<td>").append(timestamp(s)).append("</td><td>");
            for (SignatureAppearance.IdentityResult r : s.identity()) {
                h.append("<div class=\"line\">").append(HtmlComposer.esc(labels.identity(r.method()))).append(" · ")
                        .append(HtmlComposer.esc(r.passed() ? labels.passLabel() : labels.failLabel())).append("</div>");
            }
            h.append("</td><td class=\"img\">");
            byte[] png = s.imagePng();
            if (png != null) {
                h.append("<img src=\"").append(SignedPdfAppender.dataUri(png)).append("\"/>");
            }
            h.append("</td></tr>");
        }
        h.append("</table>");
        h.append("<p class=\"hash\">").append(HtmlComposer.esc(labels.originalPdfLabel())).append("<br/>").append(pdfHash).append("</p>");
        h.append("</body></html>");
        return h.toString();
    }

    /** 열 폭(%): 서명자·경로·방법·서명 시각·본인확인·서명 이미지. */
    private static final int[] COLUMN_PERCENT = {11, 13, 12, 18, 24, 22};

    /** {@code 2026-09-24} 줄바꿈 {@code 10:15:00 +09:00}(초 단위, 로케일 없는 표기). */
    private static String timestamp(SignatureAppearance s) {
        OffsetDateTime t = s.signedAt().atOffset(KST).truncatedTo(ChronoUnit.SECONDS);
        return t.toLocalDate() + "<br/>" + SignedPdfAppender.two(t.getHour()) + ":" + SignedPdfAppender.two(t.getMinute()) + ":"
                + SignedPdfAppender.two(t.getSecond()) + " +09:00";
    }

    private static String css(String footer) {
        return "@page { size: A4 portrait; margin: 16mm 12mm 18mm 12mm;"
                + " @bottom-center { content: \"" + footer + "\"; font-family: '" + RenderAssets.FONT_FAMILY + "'; font-size: 7.5pt; } }"
                + " body { font-family: '" + RenderAssets.FONT_FAMILY + "'; font-size: 9pt; line-height: 1.35; }"
                + " h1 { font-size: 15pt; font-weight: 700; text-align: center; margin: 0 0 6mm 0; }"
                + " table { border-collapse: collapse; width: 100%; margin: 0 0 4mm 0; table-layout: fixed; }"
                + " th, td { border: 0.5pt solid #000000; padding: 1.2mm 1.5mm; vertical-align: top; text-align: left; word-wrap: break-word; }"
                + " th { font-weight: 700; background-color: #EEEEEE; }"
                + " td.img { height: 18mm; }"
                + " td.img img { max-width: 100%; max-height: 16mm; }"
                + " div.line { margin: 0 0 0.8mm 0; }"
                + " p.hash { font-size: 8pt; word-wrap: break-word; }";
    }
}
