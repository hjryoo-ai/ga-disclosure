package com.ga.disclosure.rules.contracts;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약 샘플(이벤트 8종·룰·서식)이 스키마를 통과하고, 필수 필드를 하나씩 제거한 변형은 전부 실패한다(Phase 0 C10).
 * Phase 1 C14: 엔진 응답의 UNAVAILABLE 항목은 등급·순위·비율 필드를 가질 수 없고, Envelope·피드는 포털 §4.1 규약과 같다.
 * 스키마 $id(https://ga.example/contracts/…)는 저장소의 contracts/ 디렉터리로 매핑한다(원격 조회 없음). OpenAPI 문서(YAML)는
 * JSON으로 바꿔 등록하고 {@code #/components/schemas/…} 포인터로 검증한다.
 */
class ContractSchemaTest {

    private static final String BASE = "https://ga.example/contracts/";
    private static final Path CONTRACTS = Path.of(System.getProperty("ga.repoRoot"), "contracts");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    static final List<String> EVENT_TYPES = List.of(
            "DisclosureCreated", "DisclosureSealed", "SignatureCaptured", "DisclosureCompleted",
            "DisclosureVoided", "DisclosureSuperseded", "PolicyLinked", "ComplianceFlagRaised");

    private static SchemaRegistry registry;

    @BeforeAll
    static void registry() {
        // $id → 저장소 파일 내용. 매핑되지 않는 IRI는 null(원격 조회하지 않는다).
        registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                b -> b.schemas(iri -> iri.startsWith(BASE) ? schemaText(iri.substring(BASE.length())) : null));
    }

    private static Schema schema(String relative) {
        return registry.getSchema(SchemaLocation.of(BASE + relative));
    }

    private static String schemaText(String relative) {
        String text = readString(relative);
        return relative.endsWith(".yaml") ? JSON.writeValueAsString(YAML.readTree(text)) : text;
    }

    private static String readString(String relative) {
        try {
            return Files.readString(CONTRACTS.resolve(relative));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode read(String relative) {
        try {
            return JSON.readTree(Files.readString(CONTRACTS.resolve(relative)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> required(JsonNode schemaNode) {
        List<String> names = new ArrayList<>();
        schemaNode.path("required").forEach(n -> names.add(n.asString()));
        return names;
    }

    // ------------------------------------------------------------------ 이벤트

    @Test
    void everyEventTypeHasSchemaAndSample() {
        for (String type : EVENT_TYPES) {
            assertThat(CONTRACTS.resolve("events/v1/payloads/" + type + ".schema.json")).isRegularFile();
            assertThat(CONTRACTS.resolve("events/v1/samples/" + type + ".json")).isRegularFile();
        }
        assertThat(read("events/v1/envelope.schema.json").path("properties").path("type").path("enum"))
                .extracting(JsonNode::asString).containsExactlyInAnyOrderElementsOf(EVENT_TYPES);
    }

    static Stream<String> eventTypes() {
        return EVENT_TYPES.stream();
    }

    @ParameterizedTest
    @MethodSource("eventTypes")
    void eventSamplePassesEnvelopeAndPayloadSchemas(String type) {
        JsonNode sample = read("events/v1/samples/" + type + ".json");
        assertThat(sample.path("type").asString()).isEqualTo(type);
        assertThat(schema("events/v1/envelope.schema.json").validate(sample)).isEmpty();
        assertThat(schema("events/v1/payloads/" + type + ".schema.json").validate(sample.path("payload"))).isEmpty();
    }

    static Stream<Arguments> eventRequiredFieldRemovals() {
        List<String> envelopeRequired = required(read("events/v1/envelope.schema.json"));
        return EVENT_TYPES.stream().flatMap(type -> Stream.concat(
                envelopeRequired.stream().map(f -> Arguments.of(type, "envelope", f)),
                required(read("events/v1/payloads/" + type + ".schema.json")).stream().map(f -> Arguments.of(type, "payload", f))));
    }

    @ParameterizedTest(name = "{0} without {1}.{2} fails")
    @MethodSource("eventRequiredFieldRemovals")
    void eventSampleMissingRequiredFieldFails(String type, String level, String field) {
        ObjectNode sample = (ObjectNode) read("events/v1/samples/" + type + ".json");
        ObjectNode target = level.equals("envelope") ? sample : (ObjectNode) sample.get("payload");
        assertThat(target.has(field)).as("sample has %s", field).isTrue();
        target.remove(field);
        List<Error> errors = schema("events/v1/envelope.schema.json").validate(sample);
        assertThat(errors).as("removing %s.%s must fail", level, field).isNotEmpty();
    }

    @Test
    void eventWithPayloadOfAnotherTypeFails() {
        ObjectNode sample = (ObjectNode) read("events/v1/samples/DisclosureSealed.json");
        sample.set("payload", read("events/v1/samples/PolicyLinked.json").get("payload"));
        assertThat(schema("events/v1/envelope.schema.json").validate(sample)).isNotEmpty();
    }

    /** 포털 설계서 v1.1 §4.1 Envelope의 필드 집합과 같다(tenantId 없음 — 발행자가 테넌트). */
    @Test
    void envelopeFollowsPortalConvention() {
        JsonNode envelope = read("events/v1/envelope.schema.json");
        assertThat(required(envelope)).containsExactly("eventId", "seq", "type", "version", "occurredAt", "aggregate", "payload");
        assertThat(envelope.path("properties").propertyNames()).containsExactlyInAnyOrder(
                "eventId", "seq", "type", "version", "occurredAt", "aggregate", "payload");
        assertThat(required(envelope.at("/properties/aggregate"))).containsExactly("kind", "id");
        assertThat(envelope.toString()).doesNotContain("TODO(confirm-portal-4.1)");
    }

    @Test
    void envelopeWithAggregateMissingKindOrUnknownVersionPayloadMismatchFails() {
        ObjectNode sample = (ObjectNode) read("events/v1/samples/DisclosureSealed.json");
        ((ObjectNode) sample.get("aggregate")).remove("kind");
        assertThat(schema("events/v1/envelope.schema.json").validate(sample)).isNotEmpty();

        ObjectNode legacy = (ObjectNode) read("events/v1/samples/DisclosureSealed.json");
        legacy.put("eventType", "DisclosureSealed");
        legacy.remove("type");
        assertThat(schema("events/v1/envelope.schema.json").validate(legacy)).as("Phase 0 초안 필드명은 거부").isNotEmpty();
    }

    private static final String FEED_RESPONSE = "api/v1/disclosure-internal.openapi.yaml"
            + "#/paths/~1internal~1v1~1events/get/responses/200/content/application~1json/schema";

    @Test
    void eventFeedResponseFollowsPortalConvention() {
        ObjectNode feed = JSON.createObjectNode();
        var events = feed.putArray("events");
        EVENT_TYPES.forEach(type -> events.add(read("events/v1/samples/" + type + ".json")));
        feed.put("nextSeq", 1008).put("headSeq", 1010).put("schemaVersion", 1);
        assertThat(schema(FEED_RESPONSE).validate(feed)).isEmpty();

        for (String field : List.of("events", "nextSeq", "headSeq", "schemaVersion")) {
            ObjectNode missing = feed.deepCopy();
            missing.remove(field);
            assertThat(schema(FEED_RESPONSE).validate(missing)).as("feed without %s", field).isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ 엔진 등급·순위 응답 (설계서 §4.1)

    private static final String ENGINE = "api/v1/engine-disclosure.openapi.yaml";
    private static final String GRADES_RESPONSE = ENGINE + "#/components/schemas/CommissionGradesResponse";

    private static ObjectNode engineExample() {
        return (ObjectNode) YAML.readTree(readString(ENGINE))
                .at("/paths/~1internal~1v1~1disclosure~1commission-grades/post/responses/200/content/application~1json/example");
    }

    private static ObjectNode result(ObjectNode response, String status) {
        for (JsonNode r : response.get("results")) {
            if (r.path("status").asString().equals(status)) {
                return (ObjectNode) r;
            }
        }
        throw new IllegalStateException("example has no " + status + " result");
    }

    @Test
    void engineExamplePassesResponseSchema() {
        assertThat(schema(GRADES_RESPONSE).validate(engineExample())).isEmpty();
    }

    static Stream<String> gradeFields() {
        return Stream.of("ratioToAvg", "grade", "gradeLabel", "gradeOrdinal", "rankInSet", "tie");
    }

    @ParameterizedTest(name = "UNAVAILABLE with {0} fails")
    @MethodSource("gradeFields")
    void unavailableResultCarryingGradeFieldFails(String field) {
        ObjectNode response = engineExample();
        ObjectNode ok = result(response, "OK");
        result(response, "UNAVAILABLE").set(field, ok.get(field));
        assertThat(schema(GRADES_RESPONSE).validate(response)).isNotEmpty();

        ObjectNode nulled = engineExample();
        result(nulled, "UNAVAILABLE").putNull(field);
        assertThat(schema(GRADES_RESPONSE).validate(nulled)).as("null이 아니라 부재여야 한다").isNotEmpty();
    }

    @ParameterizedTest(name = "OK without {0} fails")
    @MethodSource("gradeFields")
    void okResultMissingGradeFieldFails(String field) {
        ObjectNode response = engineExample();
        result(response, "OK").remove(field);
        assertThat(schema(GRADES_RESPONSE).validate(response)).isNotEmpty();
    }

    @Test
    void unavailableWithoutReasonOrWithUnknownFieldFails() {
        ObjectNode noReason = engineExample();
        result(noReason, "UNAVAILABLE").remove("reason");
        assertThat(schema(GRADES_RESPONSE).validate(noReason)).isNotEmpty();

        ObjectNode extra = engineExample();
        result(extra, "OK").put("commissionRate", "0.12");
        assertThat(schema(GRADES_RESPONSE).validate(extra)).as("수수료율 원 수치는 응답에 없다").isNotEmpty();
    }

    @Test
    void tieBreakIsRequiredAndClosed() {
        ObjectNode missing = engineExample();
        missing.remove("tieBreak");
        assertThat(schema(GRADES_RESPONSE).validate(missing)).isNotEmpty();

        ObjectNode unknown = engineExample();
        unknown.put("tieBreak", "DENSE_RANK");
        assertThat(schema(GRADES_RESPONSE).validate(unknown)).isNotEmpty();

        ObjectNode strict = engineExample();
        strict.put("tieBreak", "STRICT");
        assertThat(schema(GRADES_RESPONSE).validate(strict)).isEmpty();
    }

    // ------------------------------------------------------------------ 룰·서식

    static Stream<Arguments> ruleAndTemplateSamples() {
        return Stream.of(
                Arguments.of("rules/v1/rule-version.schema.json", "rules/v1/DISC-2026-07.json"),
                Arguments.of("rules/v1/form-template.schema.json", "rules/v1/STANDARD-v1.json"));
    }

    @ParameterizedTest
    @MethodSource("ruleAndTemplateSamples")
    void ruleAndTemplateSamplesPass(String schemaPath, String samplePath) {
        assertThat(schema(schemaPath).validate(read(samplePath))).isEmpty();
    }

    static Stream<Arguments> ruleAndTemplateRequiredFieldRemovals() {
        return ruleAndTemplateSamples().flatMap(a -> {
            String schemaPath = (String) a.get()[0];
            String samplePath = (String) a.get()[1];
            return required(read(schemaPath)).stream().map(f -> Arguments.of(schemaPath, samplePath, f));
        });
    }

    @ParameterizedTest(name = "{1} without {2} fails")
    @MethodSource("ruleAndTemplateRequiredFieldRemovals")
    void ruleAndTemplateMissingRequiredFieldFails(String schemaPath, String samplePath, String field) {
        ObjectNode sample = (ObjectNode) read(samplePath);
        assertThat(sample.has(field)).isTrue();
        sample.remove(field);
        assertThat(schema(schemaPath).validate(sample)).isNotEmpty();
    }

    @Test
    void ruleSampleIsAppendixDVerbatim() {
        JsonNode rule = read("rules/v1/DISC-2026-07.json");
        assertThat(rule.path("minCompare").asInt()).isEqualTo(3);
        assertThat(rule.path("signerSet")).extracting(JsonNode::asString).containsExactly("CUSTOMER", "AGENT", "MANAGER");
        assertThat(rule.path("validations")).hasSize(12);
        assertThat(rule.path("reasonCodes")).hasSize(5);
    }

    @Test
    void templateFieldMissingRequiredAttributeFails() {
        JsonNode schemaNode = read("rules/v1/form-template.schema.json").path("$defs").path("field");
        for (String attribute : required(schemaNode)) {
            ObjectNode sample = (ObjectNode) read("rules/v1/STANDARD-v1.json");
            ((ObjectNode) sample.get("fields").get(0)).remove(attribute);
            assertThat(schema("rules/v1/form-template.schema.json").validate(sample)).as("field without %s", attribute).isNotEmpty();
        }
    }

    @Test
    void templateCarriesOnlyPressReleaseLabelsAndTodoPlaceholder() {
        JsonNode template = read("rules/v1/STANDARD-v1.json");
        assertThat(template.path("fields")).extracting(f -> f.path("label").asString()).containsExactly(
                "보험회사명", "비교상품군", "상품명", "보험료", "해약환급예시", "판매수수료등급", "판매수수료순위", "추천사유", "추천가능보험사");
        assertThat(template.path("pendingConfirmation")).extracting(p -> p.path("ref").asString()).containsExactly("TODO(confirm#2)");
    }

    // ------------------------------------------------------------------ OpenAPI

    @Test
    void openApiDocumentsParseAndDeclareExpectedOperations() throws IOException {
        JsonNode engine = YAML.readTree(Files.readString(CONTRACTS.resolve("api/v1/engine-disclosure.openapi.yaml")));
        JsonNode internal = YAML.readTree(Files.readString(CONTRACTS.resolve("api/v1/disclosure-internal.openapi.yaml")));
        assertThat(engine.path("openapi").asString()).startsWith("3.1");
        assertThat(internal.path("openapi").asString()).startsWith("3.1");
        assertThat(engine.path("paths").has("/internal/v1/disclosure/commission-grades")).isTrue();
        assertThat(engine.at("/paths/~1internal~1v1~1disclosure~1commission-grades~1{snapshotId}/get/operationId").asString())
                .isEqualTo("getCommissionGradesSnapshot");
        assertThat(internal.path("paths").has("/internal/v1/disclosures/gate")).isTrue();
        assertThat(internal.path("paths").has("/internal/v1/disclosures/{no}/policy-link")).isTrue();
        assertThat(internal.path("paths").has("/internal/v1/events")).isTrue();

        JsonNode ok = engine.at("/components/schemas/GradeResultOk/properties");
        assertThat(ok.has("gradeOrdinal")).isTrue();
        assertThat(ok.path("ratioToAvg").path("type").asString()).as("ratioToAvg는 불투명 문자열").isEqualTo("string");
        assertThat(internal.at("/components/schemas/GateResponse/required")).extracting(JsonNode::asString)
                .contains("pendingRoles", "gateSatisfied");
    }
}
