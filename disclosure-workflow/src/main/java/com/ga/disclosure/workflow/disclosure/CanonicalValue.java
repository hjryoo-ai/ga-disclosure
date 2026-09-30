package com.ga.disclosure.workflow.disclosure;

import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.charset.StandardCharsets;

/**
 * JSON 값 하나의 RFC 8785 정규형 텍스트. 플랫폼 정규화기는 최상위가 객체·배열인 문서만 받으므로, 스칼라(문자열·정수·불리언)는 한 원소 배열로
 * 감싸 정규화한 뒤 바깥 대괄호를 벗긴다 — 배열 원소의 직렬화가 곧 그 값의 정규형이다(RFC 8785 §3.2).
 */
public final class CanonicalValue {

    private CanonicalValue() {
    }

    public static String of(JsonNode value) {
        if (value.isContainer()) {
            return new String(Canonicalizer.canonicalize(value), StandardCharsets.UTF_8);
        }
        String wrapped = new String(Canonicalizer.canonicalize(JsonNodeFactory.instance.arrayNode().add(value)), StandardCharsets.UTF_8);
        return wrapped.substring(1, wrapped.length() - 1);
    }
}
