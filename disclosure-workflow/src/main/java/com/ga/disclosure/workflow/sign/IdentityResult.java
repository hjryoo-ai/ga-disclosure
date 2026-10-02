package com.ga.disclosure.workflow.sign;

import com.ga.disclosure.domain.enums.IdentityMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 본인확인 수단 하나의 결과(V8 {@code signature.identity_check} 배열 원소 — 증거 매니페스트 {@code identityCheck}와 같은 모양 {@code {type, result, at}}).
 * 결과만이다 — 입력값(생년월일 등)은 어떤 형태로도 담지 않는다(절대 규칙 6, 4 계획 §2.4).
 */
public record IdentityResult(IdentityMethod method, boolean passed, Instant at) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public IdentityResult {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(at, "at");
    }

    public static ArrayNode toJson(List<IdentityResult> results) {
        ArrayNode a = JSON.createArrayNode();
        results.forEach(r -> a.addObject().put("type", r.method().name()).put("result", r.passed() ? "PASS" : "FAIL").put("at", r.at().toString()));
        return a;
    }

    public static List<IdentityResult> fromJson(JsonNode a) {
        List<IdentityResult> out = new ArrayList<>();
        a.forEach(n -> out.add(new IdentityResult(IdentityMethod.valueOf(n.get("type").asString()), "PASS".equals(n.get("result").asString()),
                Instant.parse(n.get("at").asString()))));
        return List.copyOf(out);
    }
}
