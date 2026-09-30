package com.ga.disclosure.rules.validation.standard;

import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 오버라이드 가능한 실패의 대상(승인이 귀속되는 값, 3A 계획 Q3)을 만든다: 문자열 필드 객체들의 <b>집합</b>이며 항목 순서와 무관하다
 * (원소를 정규형 텍스트 순으로 정렬한 배열). 이 클래스는 엔진 스냅샷 타입을 다루지 않는다 — 스냅샷을 다루는 규칙은 필요한 문자열만
 * 꺼내 넘긴다(정렬 API는 {@code GradeConsistencyCheck} 밖에서 스냅샷 타입과 함께 쓰지 않는다, 아키텍처 규칙 (e)③).
 */
final class OverrideSubject {

    private OverrideSubject() {
    }

    static JsonNode setOf(List<Map<String, String>> entries) {
        List<ObjectNode> nodes = new ArrayList<>();
        for (Map<String, String> entry : entries) {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            entry.forEach(n::put);
            nodes.add(n);
        }
        nodes.sort(Comparator.comparing(n -> new String(Canonicalizer.canonicalize(n), StandardCharsets.UTF_8)));
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        nodes.forEach(array::add);
        return array;
    }
}
