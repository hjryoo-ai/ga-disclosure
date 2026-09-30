package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.disclosure.FieldValue;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.rules.template.FieldScope;
import com.ga.disclosure.rules.template.TemplateField;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R-FIELD-REQUIRED가 보는 서식 항목값(검증 단면). 서식은 항목의 <b>출처</b>({@code source})만 말하고 어느 값에 묶이는지는 말하지 않으므로
 * (설계서 v1.6까지 미정), 3A는 출처별로 다음을 기본 가정으로 한다 — 코드에 항목 코드를 두지 않는다(절대 규칙 4).
 * <ul>
 *   <li>CATALOG·PER_ITEM: 저장된 항목값(카탈로그 상품은 {@code defaults}에서 같은 코드의 값, 임시등록은 설계사 입력).</li>
 *   <li>CATALOG·PER_DOCUMENT: 헤더 상품군(초안 생성 때 카탈로그에서 확인) — 상품군 레코드가 확인서당 유일한 카탈로그 사실이다.</li>
 *   <li>ENGINE·PER_ITEM: 항목에 등급 복사본(엔진 OK·산출불가 또는 로컬 산출불가)이 있으면 존재. 산출불가는 서식의 {@code unavailableText}로 인쇄.</li>
 *   <li>AGENT·PER_ITEM: 저장된 설계사 입력, 없으면 추천 항목은 추천사유(코드), 추천이 아닌 항목은 설계사의 비추천 판단이 곧 값이다.</li>
 *   <li>SYSTEM: 시스템이 봉인 때 만든다(예: 추천가능 보험사 = 패널, R-PANEL이 검사) — 항상 존재.</li>
 * </ul>
 * 값 텍스트는 존재 판정용 표지이며 렌더링 값이 아니다. 항목 코드 ↔ 값의 명시적 결속(서식 데이터)은 3B 렌더링 전 결정 사항이다
 * TODO(confirm#2): 협회 표준서식 확정 시 서식 데이터에 결속을 둔다.
 */
final class FieldValueView {

    static final String ENGINE_MARK = "<ENGINE>";
    static final String SYSTEM_MARK = "<SYSTEM>";
    static final String CATALOG_GROUP_MARK = "<CATALOG:GROUP>";
    static final String NOT_RECOMMENDED_MARK = "<AGENT:NOT_RECOMMENDED>";

    private FieldValueView() {
    }

    static Map<String, String> item(TemplateResolution template, DisclosureItem item) {
        Map<String, String> out = new LinkedHashMap<>();
        item.draft().fieldValues().forEach((code, value) -> out.put(code, text(value)));
        for (TemplateField f : template.fields()) {
            if (f.scope() != FieldScope.PER_ITEM || out.containsKey(f.code())) {
                continue;
            }
            switch (f.source()) {
                case ENGINE -> item.grade().ifPresent(g -> out.put(f.code(), ENGINE_MARK + gradeKind(g)));
                case AGENT -> {
                    if (item.recommendation().isPresent()) {
                        out.put(f.code(), String.join(",", item.recommendation().get().codes().stream().map(Object::toString).toList()));
                    } else if (!item.draft().recommended()) {
                        out.put(f.code(), NOT_RECOMMENDED_MARK);
                    }
                }
                case SYSTEM -> out.put(f.code(), SYSTEM_MARK);
                case CATALOG -> {
                    // 저장값이 없으면 비어 있다 — 카탈로그 기본값에 그 코드가 없거나 임시등록 입력이 빠졌다(R-FIELD-REQUIRED가 잡는다)
                }
            }
        }
        return out;
    }

    static Map<String, String> document(TemplateResolution template) {
        Map<String, String> out = new LinkedHashMap<>();
        for (TemplateField f : template.fields()) {
            if (f.scope() != FieldScope.PER_DOCUMENT) {
                continue;
            }
            switch (f.source()) {
                case CATALOG -> out.put(f.code(), CATALOG_GROUP_MARK);
                case SYSTEM -> out.put(f.code(), SYSTEM_MARK);
                case ENGINE, AGENT -> {
                    // 확인서당 한 값인 엔진·설계사 항목은 3A에 저장 위치가 없다(현행 서식에 없음) — 있으면 비어 있어 검증이 잡는다
                }
            }
        }
        return out;
    }

    private static String gradeKind(ItemGrade g) {
        return switch (g) {
            case ItemGrade.Ok ok -> ":OK";
            case ItemGrade.Unavailable u -> ":UNAVAILABLE";
        };
    }

    /** 문자열 값은 내용 그대로(빈 문자열은 빈 값), 그 밖의 JSON은 정규형 텍스트. */
    static String text(FieldValue value) {
        JsonNode node = Canonicalizer.parseStrict(value.canonicalJson());
        return node.isString() ? node.asString() : value.canonicalJson();
    }
}
