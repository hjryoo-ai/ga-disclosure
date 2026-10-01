package com.ga.disclosure.rules.template;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 서식 본문의 구조 검사(스키마가 표현하지 못하는 것, 3B): 항목 코드 유일, 모든 항목이 배치 섹션 하나에 정확히 한 번, 섹션 방향과 항목 범위의
 * 일치(DOCUMENT ↔ PER_DOCUMENT, COLUMN_PER_ITEM ↔ PER_ITEM), 식별부 항목은 식별부끼리만 한 섹션에. 렌더러가 배치만으로 모든 항목을 인쇄할 수
 * 있게 하는 전제다. 스키마 검증을 통과한 본문에 대해 부른다.
 */
public final class TemplateLayoutCheck {

    private TemplateLayoutCheck() {
    }

    public static List<String> problems(JsonNode body) {
        List<String> problems = new ArrayList<>();
        Map<String, JsonNode> fields = new HashMap<>();
        for (JsonNode f : body.path("fields")) {
            String code = f.path("code").asString();
            if (fields.put(code, f) != null) {
                problems.add("field " + code + " appears twice");
            }
        }
        Set<String> placed = new HashSet<>();
        for (JsonNode s : body.path("layout").path("sections")) {
            String section = s.path("code").asString();
            String expectedScope = switch (s.path("orientation").asString()) {
                case "DOCUMENT" -> "PER_DOCUMENT";
                case "COLUMN_PER_ITEM" -> "PER_ITEM";
                default -> null;
            };
            Set<String> kinds = new HashSet<>();
            for (JsonNode c : s.path("fields")) {
                String code = c.asString();
                JsonNode f = fields.get(code);
                if (f == null) {
                    problems.add("layout section " + section + " names unknown field " + code);
                    continue;
                }
                if (!placed.add(code)) {
                    problems.add("field " + code + " is placed in more than one layout section");
                }
                if (expectedScope != null && !expectedScope.equals(f.path("render").path("scope").asString())) {
                    problems.add("field " + code + " (" + f.path("render").path("scope").asString() + ") cannot sit in "
                            + s.path("orientation").asString() + " section " + section);
                }
                kinds.add(f.path("section").asString());
            }
            if (kinds.size() > 1) {
                problems.add("layout section " + section + " mixes identification (HEADER) and comparison fields");
            }
        }
        fields.keySet().stream().filter(c -> !placed.contains(c)).sorted()
                .forEach(c -> problems.add("field " + c + " is not placed in any layout section"));
        return problems;
    }
}
