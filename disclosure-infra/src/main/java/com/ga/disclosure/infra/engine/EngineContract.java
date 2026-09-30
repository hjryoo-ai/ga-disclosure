package com.ga.disclosure.infra.engine;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 엔진 계약 스키마(클래스패스 {@code ga-contracts/api/v1/engine-disclosure.openapi.yaml} — 저장소 {@code contracts/}의 정본을 빌드가 복사한다).
 * 요청·응답·오류 본문을 {@code #/components/schemas/…} 포인터로 검증한다(oneOf 분기·{@code additionalProperties: false} 포함). 원격 조회는 없다.
 * 클라이언트와 테스트 대역({@code FakeEngine})이 같은 인스턴스 규칙을 쓴다.
 */
public final class EngineContract {

    private static final String BASE = "https://ga.example/contracts/";
    private static final String FILE = "api/v1/engine-disclosure.openapi.yaml";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final EngineContract INSTANCE = new EngineContract();

    private final Schema request;
    private final Schema response;
    private final Schema problem;
    private final String version;

    private EngineContract() {
        JsonNode doc = YAMLMapper.builder().build().readTree(read(FILE));
        String json = JSON.writeValueAsString(doc);
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                b -> b.schemas(iri -> iri.equals(BASE + FILE) ? json : null));
        this.request = registry.getSchema(SchemaLocation.of(BASE + FILE + "#/components/schemas/CommissionGradesRequest"));
        this.response = registry.getSchema(SchemaLocation.of(BASE + FILE + "#/components/schemas/CommissionGradesResponse"));
        this.problem = registry.getSchema(SchemaLocation.of(BASE + FILE + "#/components/schemas/Problem"));
        this.version = doc.at("/info/version").asString();
    }

    public static EngineContract get() {
        return INSTANCE;
    }

    private static String read(String relative) {
        try (InputStream in = EngineContract.class.getResourceAsStream("/ga-contracts/" + relative)) {
            if (in == null) {
                throw new IllegalStateException("contract " + relative + " is not on the classpath");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 계약 문서 버전(예: {@code 1.2.0}). */
    public String version() {
        return version;
    }

    public List<String> requestErrors(JsonNode body) {
        return request.validate(body).stream().map(Object::toString).toList();
    }

    public List<String> responseErrors(JsonNode body) {
        return response.validate(body).stream().map(Object::toString).toList();
    }

    public List<String> problemErrors(JsonNode body) {
        return problem.validate(body).stream().map(Object::toString).toList();
    }
}
