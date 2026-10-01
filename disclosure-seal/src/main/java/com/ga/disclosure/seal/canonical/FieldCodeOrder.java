package com.ga.disclosure.seal.canonical;

import com.ga.disclosure.domain.disclosure.FieldValue;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 항목값을 서식 항목 코드(문자열) 순으로 둔다 — 봉인 본문 트리의 키 순서가 JVM마다(불변 맵의 무작위 순회 순서) 흔들리지 않게. JCS 바이트는
 * 키를 다시 정렬하므로 해시와는 무관하다. 등급·비율 타입을 모르는 클래스에 둔다(정렬 금지 규칙은 그 타입을 다루는 클래스의 정렬을 막는다 —
 * 규칙을 좁히지 않고 코드를 옮겼다, CLAUDE.md).
 */
final class FieldCodeOrder {

    private FieldCodeOrder() {
    }

    static SortedMap<String, FieldValue> byCode(Map<String, FieldValue> values) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(values));
    }
}
