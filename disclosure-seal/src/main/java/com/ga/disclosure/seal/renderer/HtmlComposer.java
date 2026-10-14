package com.ga.disclosure.seal.renderer;

import com.ga.disclosure.rules.template.BindingResolver;
import com.ga.disclosure.rules.template.BindingView;
import com.ga.disclosure.rules.template.Bound;
import com.ga.disclosure.rules.template.FieldScope;
import com.ga.disclosure.rules.template.LayoutSection;
import com.ga.disclosure.rules.template.TemplateField;
import com.ga.disclosure.rules.template.TemplateResolution;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 서식 배치({@code layout})와 결속({@code bind})만으로 XHTML을 짓는다(3B 계획 §5). 항목 코드·라벨·섹션 이름·제목은 전부 서식 데이터이고 렌더러에는
 * 문구 리터럴이 없다. 값 표기는 로케일 없는 원문이다(승인 Q10): 문자열 그대로, 정수는 자릿수 구분 없이, 배열은 줄마다, 객체는 키·값 표.
 * 템플릿 엔진 없이 {@link StringBuilder}로 만들고 이스케이프는 XML 다섯 문자다.
 * <p>판({@link RendererVersion}): V1 경로는 동결이다(골든이 지킨다). V2는 서식 {@code render.columns}가 있는 항목의 객체 배열 값을 머리행 + 서식이 정한 열의 표
 * 하나로 그리고(열 키가 값에 없으면 {@code unavailableText}), 그 표의 CSS를 덧붙인다. V1은 {@code columns} 서식을 거부한다.
 */
final class HtmlComposer {

    private HtmlComposer() {
    }

    /** 각주 문자열에 들어가는 값(확인서 번호·해시)은 CSS 문자열 이스케이프가 필요 없는 문자로만 이뤄져야 한다. */
    private static final java.util.regex.Pattern FOOTER_SAFE = java.util.regex.Pattern.compile("[A-Za-z0-9_-]+");

    static String compose(RendererVersion version, TemplateResolution template, BindingView view, String disclosureNo, String hash12) {
        if (!FOOTER_SAFE.matcher(disclosureNo).matches() || !FOOTER_SAFE.matcher(hash12).matches()) {
            throw new IllegalArgumentException("footer values must be [A-Za-z0-9_-]");
        }
        String footer = disclosureNo + " \u00B7 " + hash12;
        StringBuilder h = new StringBuilder(16_384);
        h.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"UTF-8\"/><title>").append(esc(template.title()))
                .append("</title><style>").append(css(footer)).append(version == RendererVersion.V1 ? "" : V2_CSS).append("</style></head><body>");
        h.append("<h1>").append(esc(template.title())).append("</h1>");
        for (LayoutSection section : template.sections()) {
            section.label().ifPresent(l -> h.append("<h2>").append(esc(l)).append("</h2>"));
            List<TemplateField> fields = section.fieldCodes().stream().map(c -> template.field(c).orElseThrow()).toList();
            switch (section.orientation()) {
                case DOCUMENT -> document(version, h, fields, view);
                case COLUMN_PER_ITEM -> columns(version, h, fields, view, section.columnsPerTable());
            }
        }
        h.append("</body></html>");
        return h.toString();
    }

    private static void document(RendererVersion version, StringBuilder h, List<TemplateField> fields, BindingView view) {
        h.append("<table class=\"doc\">");
        for (TemplateField f : fields) {
            requireScope(f, FieldScope.PER_DOCUMENT);
            h.append("<tr><th>").append(esc(f.label())).append("</th><td>");
            cell(version, h, f, BindingResolver.resolve(f, view));
            h.append("</td></tr>");
        }
        h.append("</table>");
    }

    /** 항목(열)이 많으면 표를 {@code perTable}열씩 나눠 같은 형태로 이어 붙인다(라벨 열 반복). 열 머리는 항목 번호. */
    private static void columns(RendererVersion version, StringBuilder h, List<TemplateField> fields, BindingView view, int perTable) {
        List<? extends BindingView.Item> items = view.items();
        for (int from = 0; from < items.size(); from += perTable) {
            int to = Math.min(items.size(), from + perTable);
            h.append("<table class=\"cmp\"><colgroup><col style=\"width: ").append(LABEL_COLUMN_PERCENT).append("%\"/>");
            String itemWidth = percent((100 - LABEL_COLUMN_PERCENT) * 100 / perTable);
            for (int i = from; i < from + perTable; i++) {
                h.append("<col style=\"width: ").append(itemWidth).append("%\"/>");
            }
            h.append("</colgroup><tr><th class=\"corner\"></th>");
            for (int i = from; i < from + perTable; i++) {
                h.append("<th class=\"no\">").append(i < to ? Integer.toString(i + 1) : "").append("</th>");
            }
            h.append("</tr>");
            for (TemplateField f : fields) {
                requireScope(f, FieldScope.PER_ITEM);
                h.append("<tr><th>").append(esc(f.label())).append("</th>");
                for (int i = from; i < from + perTable; i++) {
                    h.append("<td>");
                    if (i < to) {
                        cell(version, h, f, BindingResolver.resolve(f, items.get(i)));
                    }
                    h.append("</td>");
                }
                h.append("</tr>");
            }
            h.append("</table>");
        }
    }

    /** 라벨 열 폭(%). 항목 열은 나머지를 표당 항목 수로 나눈다. */
    private static final int LABEL_COLUMN_PERCENT = 20;

    /** 1/100 % 단위 정수를 "x.yy"로(로케일 없는 표기 — String.format을 쓰지 않는다). */
    private static String percent(int hundredths) {
        int frac = hundredths % 100;
        return (hundredths / 100) + "." + (frac < 10 ? "0" : "") + frac;
    }

    private static void requireScope(TemplateField f, FieldScope scope) {
        if (f.scope() != scope) {
            throw new IllegalStateException(f.code() + " is " + f.scope() + " but sits in a " + scope + " section");
        }
    }

    private static void cell(RendererVersion version, StringBuilder h, TemplateField f, Optional<Bound> bound) {
        Optional<List<TemplateField.Column>> columns = f.columns();
        if (columns.isPresent() && version == RendererVersion.V1) {
            throw new IllegalStateException(f.code() + " uses render.columns, which renderer 1 does not draw");
        }
        if (bound.isEmpty()) {
            return;                                     // 필수가 아닌 항목의 빈 값(필수 항목은 봉인 검증이 이미 확인했다)
        }
        switch (bound.get()) {
            case Bound.Value v when columns.isPresent() && objectRows(v.value()) ->
                    table(h, columns.get(), v.value(), f.render().path("unavailableText").asString());
            case Bound.Value v -> value(h, v.value());
            case Bound.Blank b -> {
            }
            case Bound.Pending p -> throw new IllegalStateException("a sealed document has no pending binding");
        }
    }

    /** 값이 비지 않은 객체 배열인가(서식 열 표의 대상). 그 밖(설계사가 입력한 문장 등)은 일반 표기 그대로. */
    private static boolean objectRows(JsonNode v) {
        if (!v.isArray() || v.isEmpty()) {
            return false;
        }
        for (JsonNode e : v) {
            if (!e.isObject()) {
                return false;
            }
        }
        return true;
    }

    /** (V2) 서식 열 표: 머리행 = 열 라벨, 행 = 배열 원소, 칸 = 그 열 키의 값(없으면 서식의 산출불가 문구 — 지어내지 않는다). 정수는 오른쪽 정렬. */
    private static void table(StringBuilder h, List<TemplateField.Column> columns, JsonNode rows, String unavailableText) {
        h.append("<table class=\"cols\"><tr>");
        for (TemplateField.Column c : columns) {
            h.append("<th>").append(esc(c.label())).append("</th>");
        }
        h.append("</tr>");
        for (JsonNode row : rows) {
            h.append("<tr>");
            for (TemplateField.Column c : columns) {
                JsonNode v = row.get(c.key());
                if (v == null || v.isNull()) {
                    h.append("<td class=\"na\">").append(esc(unavailableText)).append("</td>");
                } else {
                    h.append(v.isIntegralNumber() ? "<td class=\"num\">" : "<td>");
                    value(h, v);
                    h.append("</td>");
                }
            }
            h.append("</tr>");
        }
        h.append("</table>");
    }

    /** V2가 V1 CSS 뒤에 덧붙이는 규칙(서식 열 표). V1 문자열은 그대로다. */
    private static final String V2_CSS = " table.cols { width: 100%; margin: 0; table-layout: auto; page-break-inside: avoid; }"
            + " table.cols th, table.cols td { border: 0.3pt solid #777777; padding: 0.6mm 1mm; font-size: 7.5pt; word-wrap: normal; }"
            + " table.cols th { font-weight: 700; background-color: #F4F4F4; text-align: center; }"
            + " table.cols td.num { text-align: right; }";

    /** 로케일 없는 원문 표기(승인 Q10). */
    private static void value(StringBuilder h, JsonNode v) {
        if (v.isString()) {
            h.append("<span class=\"text\">").append(esc(v.asString())).append("</span>");
        } else if (v.isIntegralNumber() || v.isBoolean()) {
            h.append(esc(v.toString()));
        } else if (v.isArray()) {
            h.append("<div class=\"list\">");
            for (JsonNode e : v) {
                h.append("<div class=\"line\">");
                value(h, e);
                h.append("</div>");
            }
            h.append("</div>");
        } else if (v.isObject()) {
            h.append("<table class=\"kv\">");
            for (Map.Entry<String, JsonNode> e : v.properties()) {
                h.append("<tr><td class=\"k\">").append(esc(e.getKey())).append("</td><td>");
                value(h, e.getValue());
                h.append("</td></tr>");
            }
            h.append("</table>");
        } else {
            throw new IllegalStateException("unsupported value type in a schema-valid document: " + v.getNodeType());
        }
    }

    /** XML 다섯 문자 이스케이프. XML 1.0에 쓸 수 없는 제어 문자(탭·줄바꿈 제외)는 공백으로 인쇄한다(봉인 본문의 원문은 그대로다). */
    static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        s.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0xFFFE || c == 0xFFFF) {
                        out.append(' ');
                    } else {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.toString();
    }

    /** 인쇄 양식(A4 세로, 동봉 폰트). 각주 = 확인서 번호 · canonical 해시 앞 12자 · 쪽/전체 쪽(텍스트, QR 없음 — §14 #9). */
    private static String css(String footer) {
        return "@page { size: A4 portrait; margin: 16mm 12mm 18mm 12mm;"
                + " @bottom-center { content: \"" + footer + " \u00B7 \" counter(page) \"/\" counter(pages);"
                + " font-family: '" + RenderAssets.FONT_FAMILY + "'; font-size: 7.5pt; } }"
                + " body { font-family: '" + RenderAssets.FONT_FAMILY + "'; font-size: 9pt; line-height: 1.35; }"
                + " h1 { font-size: 15pt; font-weight: 700; text-align: center; margin: 0 0 6mm 0; }"
                + " h2 { font-size: 10.5pt; font-weight: 700; margin: 4mm 0 2mm 0; }"
                + " table { border-collapse: collapse; width: 100%; margin: 0 0 4mm 0; page-break-inside: avoid; table-layout: fixed; }"
                + " th, td { border: 0.5pt solid #000000; padding: 1.2mm 1.5mm; vertical-align: top; text-align: left;"
                + " word-wrap: break-word; }"
                + " th { font-weight: 700; background-color: #EEEEEE; }"
                + " table.doc th { width: 28%; }"
                               + " th.no { text-align: center; }"
                + " table.kv { width: 100%; margin: 0; table-layout: auto; }"
                + " table.kv td { border: 0.3pt solid #777777; padding: 0.6mm 1mm; font-size: 7.5pt; word-wrap: normal; }"
                + " span.text { white-space: pre-wrap; }"
                + " div.line { margin: 0 0 0.8mm 0; }";
    }
}
