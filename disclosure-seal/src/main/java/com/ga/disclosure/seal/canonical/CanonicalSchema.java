package com.ga.disclosure.seal.canonical;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 봉인 본문 스키마({@code contracts/seal/v1/canonical.schema.json}) 검증. 이 스키마가 봉인 본문의 정본 정의다 — 빌더는 산출물을 이 스키마로
 * 검증하고 위반이면 봉인하지 않는다. 스키마 파일은 빌드가 클래스패스 {@code ga-contracts/seal/}에 싣는다(원격 조회 없음).
 */
public final class CanonicalSchema {

    private static final String BASE = "https://ga.example/contracts/";
    private static final String RESOURCE_ROOT = "/ga-contracts/";
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemas(iri -> iri.startsWith(BASE) ? resource(iri.substring(BASE.length())) : null));
    private static final Schema CANONICAL = REGISTRY.getSchema(SchemaLocation.of(BASE + "seal/v1/canonical.schema.json"));

    private CanonicalSchema() {
    }

    public static List<String> validate(JsonNode document) {
        return CANONICAL.validate(document).stream().map(Object::toString).toList();
    }

    private static String resource(String relative) {
        try (InputStream in = CanonicalSchema.class.getResourceAsStream(RESOURCE_ROOT + relative)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
