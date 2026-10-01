package com.ga.disclosure.seal.canonical;

import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Objects;

/**
 * 스키마를 통과한 봉인 본문. {@link #bytes()} = JCS 바이트(첫 바이트는 언제나 {@code '{'} — 최상위가 객체, 3A D7의 {@code [x]} 감싸기 규약은
 * 봉인 경로에 없다), {@link #sha256()} = {@code canonical_hash}.
 */
public final class CanonicalDocument {

    private final JsonNode json;
    private final byte[] bytes;
    private final String sha256;

    private CanonicalDocument(JsonNode json, byte[] bytes) {
        this.json = json;
        this.bytes = bytes;
        this.sha256 = Sha256.of(bytes);
    }

    /** 스키마 검증 → JCS. 위반이면 {@link CanonicalSchemaViolation}(봉인하지 않는다). */
    static CanonicalDocument of(JsonNode json) {
        Objects.requireNonNull(json, "json");
        if (!json.isObject()) {
            throw new CanonicalSchemaViolation(java.util.List.of("canonical document must be a JSON object"));
        }
        var problems = CanonicalSchema.validate(json);
        if (!problems.isEmpty()) {
            throw new CanonicalSchemaViolation(problems);
        }
        return new CanonicalDocument(json.deepCopy(), Canonicalizer.canonicalize(json));
    }

    /** 저장된 JCS 바이트에서 복원(재렌더·검증용). 바이트가 그 자체의 정규형이고 스키마를 통과해야 한다. */
    public static CanonicalDocument parse(byte[] jcsBytes) {
        JsonNode json = Canonicalizer.parseStrict(new String(jcsBytes, java.nio.charset.StandardCharsets.UTF_8));
        CanonicalDocument doc = of(json);
        if (!Arrays.equals(doc.bytes, jcsBytes)) {
            throw new CanonicalSchemaViolation(java.util.List.of("bytes are not the JCS form of the document"));
        }
        return doc;
    }

    public JsonNode json() {
        return json.deepCopy();
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String sha256() {
        return sha256;
    }

    @Override
    public String toString() {
        return "CanonicalDocument[" + sha256 + "]";       // 본문(성명 포함)은 문자열로 내보내지 않는다
    }
}
