package com.ga.disclosure.rules.bundle;

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
 * 계약 스키마(contracts/rules/v1, JSON Schema 2020-12) 검증. 스키마 파일은 빌드가 클래스패스 {@code ga-contracts/rules/}에 싣고,
 * {@code $id}(https://ga.example/contracts/…)를 그 경로로 매핑한다 — 원격 조회는 없다.
 */
public final class RuleSchemas {

    private static final String BASE = "https://ga.example/contracts/";
    private static final String RESOURCE_ROOT = "/ga-contracts/";

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemas(iri -> iri.startsWith(BASE) ? resource(iri.substring(BASE.length())) : null));

    private static final Schema BUNDLE = schema("rules/v1/rule-bundle.schema.json");
    private static final Schema RULE_BODY = schema("rules/v1/rule-version.schema.json");
    private static final Schema TEMPLATE_BODY = schema("rules/v1/form-template.schema.json");

    private RuleSchemas() {
    }

    /** 번들 파일 전체(메타 + body). body는 kind에 따라 룰 또는 서식 스키마로 검증된다. */
    public static List<String> validateBundle(JsonNode bundle) {
        return errors(BUNDLE, bundle);
    }

    /** 완전한 룰 본문(GLOBAL 본문, 또는 GLOBAL 위에 TENANT를 병합한 결과). */
    public static List<String> validateRuleBody(JsonNode body) {
        return errors(RULE_BODY, body);
    }

    /** 서식 본문({@code fields}, {@code layout}, {@code pendingConfirmation}). */
    public static List<String> validateTemplateBody(JsonNode body) {
        return errors(TEMPLATE_BODY, body);
    }

    private static List<String> errors(Schema schema, JsonNode node) {
        return schema.validate(node).stream().map(Object::toString).toList();
    }

    private static Schema schema(String relative) {
        return REGISTRY.getSchema(SchemaLocation.of(BASE + relative));
    }

    private static String resource(String relative) {
        try (InputStream in = RuleSchemas.class.getResourceAsStream(RESOURCE_ROOT + relative)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
