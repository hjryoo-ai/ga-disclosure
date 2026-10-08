package com.ga.disclosure.app.api;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 시험용 OpenAPI 계약(6A 계획 §4.2, G11): 저장소 {@code contracts/api/v1}의 정본 YAML을 그대로 읽어 경로·메서드·상태별 응답 스키마로 응답을 검증한다.
 * {@link ApiTestSupport#send}가 {@code /api}·{@code /internal} 응답마다 부른다 — 모든 IT 응답이 계약을 통과한다. 계약에 없는 경로(없는 라우트·체인 밖)는
 * 오류 본문 {@code Problem}으로 검증한다. {@code x-ga-phase}(6B)·{@code x-ga-pending} 경로는 아직 구현이 없으므로 대상 밖이다.
 */
final class ApiContracts {

    static final String BASE = "https://ga.example/contracts/";
    static final List<String> FILES = List.of("api/v1/disclosure-api.openapi.yaml", "api/v1/disclosure-internal.openapi.yaml");
    static final List<String> METHODS = List.of("get", "post", "put", "delete", "patch");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final ApiContracts INSTANCE = new ApiContracts();

    record Operation(String file, String method, String template, Pattern path, JsonNode node) {
    }

    final Map<String, JsonNode> docs = new LinkedHashMap<>();
    final List<Operation> operations = new ArrayList<>();
    final List<String> unimplemented = new ArrayList<>();
    private final SchemaRegistry registry;

    private ApiContracts() {
        Path root = ApiTestSupport.ROOT.resolve("contracts");
        for (String f : FILES) {
            docs.put(f, YAML.readTree(read(root.resolve(f))));
        }
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> b.schemas(iri -> {
            if (!iri.startsWith(BASE)) {
                return null;
            }
            String relative = iri.substring(BASE.length());
            if (docs.containsKey(relative)) {
                return JSON.writeValueAsString(docs.get(relative));
            }
            Path file = root.resolve(relative).normalize();
            return file.startsWith(root) && Files.isRegularFile(file) ? read(file) : null;
        }));
        docs.forEach((file, doc) -> doc.get("paths").properties().forEach(e -> {
            String template = e.getKey();
            JsonNode item = e.getValue();
            for (String method : METHODS) {
                if (!item.has(method)) {
                    continue;
                }
                if (item.has("x-ga-phase") || item.has("x-ga-pending")) {
                    unimplemented.add(method.toUpperCase() + " " + template);
                    continue;
                }
                operations.add(new Operation(file, method, template, Pattern.compile("^" + template.replaceAll("\\{[^/]+}", "[^/]+") + "$"),
                        item.get(method)));
            }
        }));
    }

    static ApiContracts get() {
        return INSTANCE;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Optional<Operation> find(String method, String path) {
        return operations.stream().filter(o -> o.method().equalsIgnoreCase(method) && o.path().matcher(path).matches()).findFirst();
    }

    /** 문서 안 노드의 JSON 포인터({@code $ref}가 같은 문서의 응답 객체면 따라간다). */
    private static String pointer(String... segments) {
        StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            sb.append('/').append(s.replace("~", "~0").replace("/", "~1"));
        }
        return sb.toString();
    }

    Schema schema(String file, String pointer) {
        return registry.getSchema(SchemaLocation.of(BASE + file + "#" + pointer));
    }

    /** 응답 한 건을 계약으로 검증한다 — 위반 목록(비면 통과). */
    List<String> violations(String method, String pathWithQuery, int status, String contentType, byte[] body) {
        String path = pathWithQuery.contains("?") ? pathWithQuery.substring(0, pathWithQuery.indexOf('?')) : pathWithQuery;
        if (!path.startsWith("/api/") && !path.startsWith("/internal/")) {
            return List.of();
        }
        Optional<Operation> op = find(method, path);
        if (op.isEmpty()) {
            return validate("api/v1/disclosure-api.openapi.yaml", "/components/schemas/Problem", body, method + " " + path + " (no route)");
        }
        Operation o = op.get();
        String file = o.file();
        String statusKey = o.node().path("responses").has(String.valueOf(status)) ? String.valueOf(status) : "default";
        JsonNode response = o.node().path("responses").path(statusKey);
        if (response.isMissingNode()) {
            return List.of(method + " " + o.template() + " does not document status " + status);
        }
        String responsePointer = pointer("paths", o.template(), o.method(), "responses", statusKey);
        if (response.has("$ref")) {
            String ref = response.get("$ref").asString();
            if (!ref.startsWith("#")) {
                return List.of("cross-file response refs are not supported: " + ref);
            }
            responsePointer = ref.substring(1);
            response = docs.get(file).at(responsePointer);
        }
        JsonNode content = response.path("content");
        if (content.isMissingNode() || content.isEmpty()) {
            return body.length == 0 ? List.of() : List.of(method + " " + o.template() + " " + status + " documents no body");
        }
        String media = contentType == null ? "" : contentType.split(";")[0].trim();
        if (!content.has(media)) {
            return List.of(method + " " + o.template() + " " + status + " does not document " + media);
        }
        if (!media.equals("application/json")) {
            return List.of();
        }
        return validate(file, responsePointer + pointer("content", media, "schema"), body, method + " " + o.template() + " " + status);
    }

    private List<String> validate(String file, String pointer, byte[] body, String where) {
        JsonNode node;
        try {
            node = JSON.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return List.of(where + ": body is not JSON");
        }
        return schema(file, pointer).validate(node).stream().map(e -> where + ": " + e).toList();
    }
}
