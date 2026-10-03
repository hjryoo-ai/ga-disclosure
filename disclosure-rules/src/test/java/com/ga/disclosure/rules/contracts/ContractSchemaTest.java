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
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
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
            "DisclosureVoided", "DisclosureSuperseded", "PolicyLinked", "ComplianceFlagRaised", "DisclosureDestroyed");

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

    // ------------------------------------------------------------------ 계약 1.2.0: 상품 키 규칙·오류 코드 (Phase 3A 선행 A)

    private static final String GRADES_REQUEST = ENGINE + "#/components/schemas/CommissionGradesRequest";
    private static final String CODE31 = "P111111111111111111111111111111";

    private static ObjectNode engineRequestExample() {
        return (ObjectNode) YAML.readTree(readString(ENGINE))
                .at("/paths/~1internal~1v1~1disclosure~1commission-grades/post/requestBody/content/application~1json/example");
    }

    private static ObjectNode requestWithKey(String productKey, String insurerCode) {
        ObjectNode request = engineRequestExample();
        ObjectNode first = (ObjectNode) request.get("products").get(0);
        first.put("productKey", productKey);
        first.put("insurerCode", insurerCode);
        return request;
    }

    @Test
    void engineContractIsVersion120AndRequestExamplePasses() {
        assertThat(YAML.readTree(readString(ENGINE)).at("/info/version").asString()).isEqualTo("1.2.0");
        assertThat(schema(GRADES_REQUEST).validate(engineRequestExample())).isEmpty();
    }

    @ParameterizedTest(name = "accepted {0}")
    @ValueSource(strings = {"INS-A:PRD-1001", "ABCDEFGH:" + "P111111111111111111111111111111", "A:1", "INS-A:p.r_d-1", "12345678:X"})
    void productKeysWithinTheRuleAreAccepted(String key) {
        assertThat(key.length()).isLessThanOrEqualTo(40);
        assertThat(schema(GRADES_REQUEST).validate(requestWithKey(key, key.substring(0, key.indexOf(':'))))).isEmpty();
    }

    @ParameterizedTest(name = "rejected {0}")
    @ValueSource(strings = {
            "ABCDEFGH:" + CODE31 + "2",         // 41자
            "ABCDEFGHI:P1",                      // 보험사 9자
            "INS_A:PRD-1",                        // 보험사에 밑줄
            "-INS:PRD-1",                         // 보험사가 하이픈으로 시작
            "INS-A:.PRD", "INS-A:-PRD",           // 상품 코드가 영숫자로 시작하지 않음
            "ins-a:PRD-1", "INS-A:PRD:1", "INS-A:", "INS-A"})
    void productKeysOutsideTheRuleAreRejected(String key) {
        assertThat(schema(GRADES_REQUEST).validate(requestWithKey(key, "INS-A"))).isNotEmpty();
    }

    @ParameterizedTest(name = "insurerCode {0} rejected")
    @ValueSource(strings = {"ABCDEFGHI", "INS_A", "ins-a", "-INS", ""})
    void insurerCodesOutsideTheRuleAreRejected(String insurer) {
        assertThat(schema(GRADES_REQUEST).validate(requestWithKey("INS-A:PRD-1", insurer))).isNotEmpty();
    }

    @Test
    void responseProductKeysFollowTheSameRule() {
        ObjectNode response = engineExample();
        result(response, "OK").put("productKey", "ABCDEFGH:" + CODE31 + "2");
        assertThat(schema(GRADES_RESPONSE).validate(response)).isNotEmpty();
    }

    /** 카탈로그 파일과 엔진 계약이 같은 키 규칙을 쓴다(카탈로그 키가 그대로 엔진 요청이 된다). */
    @Test
    void catalogFileUsesTheEngineKeyRule() {
        JsonNode engine = YAML.readTree(readString(ENGINE)).at("/components/schemas");
        JsonNode catalog = read("catalog/v1/catalog-file.schema.json").path("$defs");
        for (String f : List.of("pattern", "maxLength")) {
            assertThat(catalog.at("/product/properties/productKey").path(f)).isEqualTo(engine.path("ProductKey").path(f));
            assertThat(catalog.path("insurerCode").path(f)).isEqualTo(engine.path("InsurerCode").path(f));
        }
    }

    /** 엔진 E3 요청 1~4: 추가형 오류 코드와 GET 403 설명 변경(엔진 E3 심사 §3-3). 임시등록은 엔진 사유 예시에서 빠진다. */
    @Test
    void engineErrorCodesOf120AreDocumented() {
        JsonNode engine = YAML.readTree(readString(ENGINE));
        JsonNode post = engine.at("/paths/~1internal~1v1~1disclosure~1commission-grades/post/responses");
        JsonNode get = engine.at("/paths/~1internal~1v1~1disclosure~1commission-grades~1{snapshotId}/get/responses");
        assertThat(post.at("/400/description").asString()).contains("AS_OF_IN_FUTURE", "UNKNOWN_PRODUCT_GROUP");
        assertThat(post.at("/422/description").asString()).contains("NO_POLICY", "POLICY_SELF_CHECK_FAILED", "INVALID_POLICY");
        assertThat(get.at("/500/description").asString()).contains("SNAPSHOT_INTEGRITY");
        assertThat(get.at("/500/content/application~1json/schema/$ref").asString()).isEqualTo("#/components/schemas/Problem");
        assertThat(get.at("/403/description").asString()).contains("인가 거부").contains("404").doesNotContain("TENANT_MISMATCH");
        assertThat(engine.at("/components/schemas/GradeResultUnavailable/properties/reason/description").asString())
                .doesNotContain("예: NO_RATE_DATA·NOT_IN_GROUP·TEMP_PRODUCT");
    }

    // ------------------------------------------------------------------ 룰·서식

    static final String DISC_2026_07 = "rules/bundles/rules/DISC-2026-07.bundle.json";
    static final String DISC_2027_01 = "rules/bundles/rules/DISC-2027-01.bundle.json";
    static final String STANDARD_V1 = "rules/bundles/templates/STANDARD-v1.bundle.json";

    static Stream<String> bundles() {
        return Stream.of(DISC_2026_07, DISC_2027_01, STANDARD_V1);
    }

    @ParameterizedTest
    @MethodSource("bundles")
    void bundleFilesPassTheBundleSchema(String bundle) {
        assertThat(schema("rules/v1/rule-bundle.schema.json").validate(read(bundle))).isEmpty();
    }

    static Stream<Arguments> bodyRequiredFieldRemovals() {
        return Stream.of(
                        Arguments.of("rules/v1/rule-version.schema.json", DISC_2026_07),
                        Arguments.of("rules/v1/form-template.schema.json", STANDARD_V1))
                .flatMap(a -> required(read((String) a.get()[0])).stream().map(f -> Arguments.of(a.get()[0], a.get()[1], f)));
    }

    @ParameterizedTest(name = "{1} body without {2} fails")
    @MethodSource("bodyRequiredFieldRemovals")
    void bundleBodyMissingRequiredFieldFails(String schemaPath, String bundlePath, String field) {
        ObjectNode body = (ObjectNode) read(bundlePath).get("body");
        assertThat(schema(schemaPath).validate(body)).isEmpty();
        assertThat(body.has(field)).isTrue();
        body.remove(field);
        assertThat(schema(schemaPath).validate(body)).isNotEmpty();
    }

    @Test
    void ruleBundleBodyIsAppendixD() {
        JsonNode rule = read(DISC_2026_07).get("body");
        assertThat(rule.path("minCompare").asInt()).isEqualTo(3);
        assertThat(rule.path("signerSet")).extracting(JsonNode::asString).containsExactly("CUSTOMER", "AGENT", "MANAGER");
        assertThat(rule.path("validations")).hasSize(12);
        assertThat(rule.path("reasonCodes")).hasSize(5);
        assertThat(rule.path("tenantOverridable")).extracting(JsonNode::asString).containsExactly(
                "signDeadlineDays", "remoteLinkTtlHours", "channels", "identityCheck", "proxySignatureDetection", "kpi",
                "retainUnlinked", "masking", "gateRequiresManager", "sessionTtlMinutes", "agentSignMethod", "retention", "customerRef");
        // Phase 5(5 계획 승인 Q5·Q7·Q10): 앵커 깊이는 GLOBAL(옛 테넌트 키 anchor 제거), 보존기간 = 년 + 일, 파기·검증 절차 파라미터
        assertThat(rule.has("anchor")).isFalse();
        assertThat(rule.at("/anchoring/treeDepth").asInt()).isEqualTo(16);
        assertThat(rule.path("retentionYears").asInt()).isEqualTo(5);
        assertThat(rule.path("retentionDays").asInt()).isZero();
        assertThat(rule.at("/retention/contractLinkWaitDays").asInt()).isEqualTo(365);
        assertThat(rule.path("legalHoldReasons")).extracting(r -> r.path("code").asString())
                .containsExactly("LITIGATION", "REGULATOR_INQUIRY", "CUSTOMER_COMPLAINT", "OTHER");
        assertThat(rule.at("/verify/unstampedAnchorAlertDays").asInt()).isEqualTo(2);
        // Phase 4(3B 수용심사 §3, 4 계획 승인 Q5·Q7): 사유 코드 닫힌 목록, 보존 앵커, 채널 객체
        assertThat(rule.path("voidReasons")).extracting(r -> r.path("code").asString())
                .containsExactly("CUSTOMER_CANCELLED", "WRITTEN_IN_ERROR", "DUPLICATE", "OTHER");
        assertThat(rule.path("supersedeReasons")).extracting(r -> r.path("code").asString())
                .containsExactly("CONTENT_ERROR", "PRODUCT_DATA_CORRECTED", "OTHER");
        assertThat(rule.path("lifecycleReasonTextMaxLength").asInt()).isEqualTo(500);
        assertThat(rule.path("retentionAnchors")).extracting(JsonNode::asString).containsExactly("SEAL", "COMPLETION", "CONTRACT_DATE");
        assertThat(rule.at("/channels/PAPER_SCAN/requiresManagerReview").asBoolean()).isTrue();
        assertThat(rule.path("allowedTieBreaks")).extracting(JsonNode::asString).containsExactly("SHARED_RANK", "STRICT");
        assertThat(rule.at("/exceptionApproval/role").asString()).isEqualTo("MANAGER");
        assertThat(rule.at("/validations/10/id").asString()).isEqualTo("R-SIGNER-SET");
        assertThat(rule.at("/validations/10/stages")).extracting(JsonNode::asString).containsExactly("COMPLETE");
    }

    /** Phase 1 수용 심사 §3-1: 단계는 기본값 없음 — 빈 단계·모르는 단계·문자열 항목(Phase 1 형식)·추가 속성은 스키마 위반. */
    @ParameterizedTest(name = "validations[0] = {0} fails")
    @ValueSource(strings = {
            "{\"id\":\"R-MIN-COMPARE\",\"stages\":[]}",
            "{\"id\":\"R-MIN-COMPARE\"}",
            "{\"id\":\"R-MIN-COMPARE\",\"stages\":[\"SIGN\"]}",
            "{\"id\":\"R-MIN-COMPARE\",\"stages\":[\"SEAL\",\"SEAL\"]}",
            "{\"id\":\"R-MIN-COMPARE\",\"stages\":[\"SEAL\"],\"default\":true}",
            "\"R-MIN-COMPARE\""})
    void validationStepsHaveExplicitClosedStages(String step) {
        ObjectNode body = (ObjectNode) read(DISC_2026_07).get("body");
        ((ArrayNode) body.get("validations")).set(0, YAML.readTree(step));
        assertThat(schema("rules/v1/rule-version.schema.json").validate(body)).isNotEmpty();
    }

    /** 예외 승인 주체는 GLOBAL 전용 — 사규에 열 수 없고, 역할 어휘는 닫혀 있다. 마스킹은 열 수 있다. */
    @Test
    void exceptionApprovalIsClosedAndCannotBeOpenedToTenants() {
        ObjectNode opened = (ObjectNode) read(DISC_2026_07).get("body");
        ((ArrayNode) opened.get("tenantOverridable")).add("exceptionApproval");
        assertThat(schema("rules/v1/rule-version.schema.json").validate(opened)).isNotEmpty();
        ObjectNode agent = (ObjectNode) read(DISC_2026_07).get("body");
        ((ObjectNode) agent.get("exceptionApproval")).put("role", "AGENT");
        assertThat(schema("rules/v1/rule-version.schema.json").validate(agent)).isNotEmpty();
        ObjectNode negative = (ObjectNode) read(DISC_2026_07).get("body");
        ((ObjectNode) negative.at("/masking/phone")).put("keepLast", -1);
        assertThat(schema("rules/v1/rule-version.schema.json").validate(negative)).isNotEmpty();
    }

    /** Phase 5(승인 Q5·Q7): 보존기간 합계는 1일 이상, 보존·앵커·보류 사유·검증 키는 사규로 열 수 없다, 옛 anchor 키는 없다. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"zero-retention", "overridable:retentionYears", "overridable:retentionDays", "overridable:anchoring",
            "overridable:legalHoldReasons", "overridable:verify", "legacy-anchor", "depth-25"})
    void retentionAndAnchoringStayGlobal(String change) {
        ObjectNode body = (ObjectNode) read(DISC_2026_07).get("body");
        switch (change) {
            case "zero-retention" -> body.put("retentionYears", 0).put("retentionDays", 0);
            case "legacy-anchor" -> body.set("anchor", YAML.readTree("{\"externalTimestamp\": true}"));
            case "depth-25" -> ((ObjectNode) body.get("anchoring")).put("treeDepth", 25);
            default -> ((ArrayNode) body.get("tenantOverridable")).add(change.substring("overridable:".length()));
        }
        assertThat(schema("rules/v1/rule-version.schema.json").validate(body)).isNotEmpty();
        ObjectNode oneDay = (ObjectNode) read(DISC_2026_07).get("body");
        oneDay.put("retentionYears", 0).put("retentionDays", 1);
        assertThat(schema("rules/v1/rule-version.schema.json").validate(oneDay)).as("0 years + 1 day is the shortest period").isEmpty();
    }

    @Test
    void templateFieldMissingRequiredAttributeFails() {
        JsonNode schemaNode = read("rules/v1/form-template.schema.json").path("$defs").path("field");
        for (String attribute : required(schemaNode)) {
            ObjectNode body = (ObjectNode) read(STANDARD_V1).get("body");
            ((ObjectNode) body.get("fields").get(0)).remove(attribute);
            assertThat(schema("rules/v1/form-template.schema.json").validate(body)).as("field without %s", attribute).isNotEmpty();
        }
    }

    @Test
    void templateCarriesOnlyPressReleaseLabelsAndTodoPlaceholder() {
        JsonNode template = read(STANDARD_V1).get("body");
        List<JsonNode> comparison = new ArrayList<>();
        List<JsonNode> header = new ArrayList<>();
        template.path("fields").forEach(f -> (f.path("section").asString().equals("HEADER") ? header : comparison).add(f));
        // 비교 항목은 금융위 보도자료의 9개뿐 — 지어낸 항목명이 없다
        assertThat(comparison).extracting(f -> f.path("label").asString()).containsExactly(
                "보험회사명", "비교상품군", "상품명", "보험료", "해약환급예시", "판매수수료등급", "판매수수료순위", "추천사유", "추천가능보험사");
        assertThat(comparison).allSatisfy(f -> assertThat(f.has("labelRef")).as(f.path("code").asString()).isFalse());
        // 문서 식별부 4개(3B 계획 승인 Q2): 비교 항목이 아니며 라벨은 정본 확인 전 가정으로 표시돼 있다
        assertThat(header).extracting(f -> f.path("render").path("bind").asString()).containsExactly(
                "HEADER_DISCLOSURE_NO", "HEADER_CONSULT_DATE", "HEADER_AGENT", "HEADER_CUSTOMER_NAME");
        assertThat(header).allSatisfy(f -> assertThat(f.path("labelRef").asString()).isEqualTo("TODO(confirm#2)"));
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

        // 1.1.0: 토큰·테넌트 불일치·스냅샷 미발급 명시 오류(Phase E3 계획 Q3)
        JsonNode post = engine.at("/paths/~1internal~1v1~1disclosure~1commission-grades/post/responses");
        JsonNode get = engine.at("/paths/~1internal~1v1~1disclosure~1commission-grades~1{snapshotId}/get/responses");
        assertThat(post.propertyNames()).containsExactlyInAnyOrder("200", "400", "401", "403", "409", "422");
        assertThat(get.propertyNames()).containsExactlyInAnyOrder("200", "401", "403", "404", "500");   // 500: 1.2.0

        JsonNode ok = engine.at("/components/schemas/GradeResultOk/properties");
        assertThat(ok.has("gradeOrdinal")).isTrue();
        assertThat(ok.path("ratioToAvg").path("type").asString()).as("ratioToAvg는 불투명 문자열").isEqualTo("string");
        assertThat(internal.at("/components/schemas/GateResponse/required")).extracting(JsonNode::asString)
                .contains("pendingRoles", "gateSatisfied");
    }
}
