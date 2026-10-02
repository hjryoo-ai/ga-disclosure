package com.ga.disclosure.infra.outbox;

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
 * 이벤트 계약 스키마(클래스패스 {@code ga-contracts/events/v1/} — 저장소 {@code contracts/events}의 정본을 빌드가 복사한다). envelope 스키마가
 * {@code type}·{@code version}별 payload 스키마를 {@code allOf/if-then}으로 고르므로 envelope 하나로 둘 다 검증한다. 원격 조회는 없다.
 */
public final class EventContract {

    private static final String BASE = "https://ga.example/contracts/";
    private static final String RESOURCE_ROOT = "/ga-contracts/";
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemas(iri -> iri.startsWith(BASE) ? resource(iri.substring(BASE.length())) : null));
    private static final Schema ENVELOPE = REGISTRY.getSchema(SchemaLocation.of(BASE + "events/v1/envelope.schema.json"));

    private EventContract() {
    }

    /** 위반 목록(비어 있으면 통과). */
    public static List<String> validate(JsonNode envelope) {
        return ENVELOPE.validate(envelope).stream().map(Object::toString).toList();
    }

    private static String resource(String relative) {
        try (InputStream in = EventContract.class.getResourceAsStream(RESOURCE_ROOT + relative)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
