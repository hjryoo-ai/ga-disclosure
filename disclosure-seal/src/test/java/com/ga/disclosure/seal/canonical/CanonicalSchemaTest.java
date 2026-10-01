package com.ga.disclosure.seal.canonical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3B S3: 봉인 본문은 스키마를 통과하고, 금지 키(번호·상태·시각·해시·체인·메타·전화·생년월일·등록 키)가 최상위나 항목에 들어가면 실패하며,
 * 최상위는 객체다(3A D7의 스칼라 {@code [x]} 감싸기는 봉인 경로에 없다). 정수는 2^53−1까지, 소수 없음.
 */
class CanonicalSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ObjectNode built() {
        return (ObjectNode) CanonicalDocumentBuilder.build(SealFixtures.case02(), SealFixtures.NAME).json();
    }

    @Test
    void builderOutputPassesAndIsAnObject() {
        for (CanonicalInput in : new CanonicalInput[] {SealFixtures.case01(), SealFixtures.case02(), SealFixtures.case03()}) {
            CanonicalDocument doc = CanonicalDocumentBuilder.build(in, SealFixtures.NAME);
            assertThat(CanonicalSchema.validate(doc.json())).isEmpty();
            assertThat(doc.bytes()[0]).as("최상위가 객체 — [x] 감싸기 없음").isEqualTo((byte) '{');
            assertThat(new String(doc.bytes(), StandardCharsets.UTF_8)).doesNotStartWith("[");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"disclosureNo", "status", "sealedAt", "canonicalHash", "pdfHash", "chainHash", "chainSeq", "retentionUntil",
            "policyNo", "contractDate", "phone", "birthDate", "registrationKey"})
    void forbiddenKeysFailAtTopLevelAndInItems(String key) {
        ObjectNode top = built();
        top.put(key, "x");
        assertThat(CanonicalSchema.validate(top)).isNotEmpty();
        ObjectNode item = built();
        ((ObjectNode) item.path("items").get(0)).put(key, "x");
        assertThat(CanonicalSchema.validate(item)).isNotEmpty();
    }

    @Test
    void anyUnknownFieldFails() {
        ObjectNode top = built();
        top.put("note", "x");
        assertThat(CanonicalSchema.validate(top)).isNotEmpty();
        ObjectNode origin = built();
        ((ObjectNode) origin.path("items").get(0)).putObject("fieldOrigins").put("PREMIUM", "CATALOG");
        assertThat(CanonicalSchema.validate(origin)).as("출처는 증거 패키지에만").isNotEmpty();
        ObjectNode omitted = built();
        omitted.remove("supersedesId");
        assertThat(CanonicalSchema.validate(omitted)).as("선택 값도 키는 생략하지 않는다(null)").isNotEmpty();
    }

    @Test
    void topLevelMustBeAnObject() {
        assertThat(CanonicalSchema.validate(JSON.createArrayNode().add(built()))).isNotEmpty();
        assertThat(CanonicalSchema.validate(JSON.getNodeFactory().stringNode("x"))).isNotEmpty();
        assertThatThrownBy(() -> CanonicalDocument.of(JSON.createArrayNode())).isInstanceOf(CanonicalSchemaViolation.class);
    }

    @Test
    void integersAreBoundedAndDecimalsAreRejected() {
        ObjectNode big = built();
        ((ObjectNode) big.path("items").get(0).path("fieldValues")).put("PREMIUM", 9007199254740992L);
        assertThat(CanonicalSchema.validate(big)).as("2^53").isNotEmpty();
        ObjectNode max = built();
        ((ObjectNode) max.path("items").get(0).path("fieldValues")).put("PREMIUM", 9007199254740991L);
        assertThat(CanonicalSchema.validate(max)).as("2^53−1").isEmpty();
        ObjectNode decimal = built();
        ((ObjectNode) decimal.path("items").get(0).path("fieldValues")).set("PREMIUM", JSON.readTree("32100.5"));
        assertThat(CanonicalSchema.validate(decimal)).isNotEmpty();
        ObjectNode basis = built();
        ((ObjectNode) basis.path("snapshot").path("basis")).set("groupPopulation", JSON.readTree("27.5"));
        assertThat(CanonicalSchema.validate(basis)).isNotEmpty();
    }

    @Test
    void ratioIsCarriedAsTheEngineOriginalString() {
        JsonNode first = CanonicalDocumentBuilder.build(SealFixtures.case01(), SealFixtures.NAME).json().path("items").get(0).path("grade");
        assertThat(first.path("ratioToAvg").isString()).isTrue();
        assertThat(first.path("ratioToAvg").asString()).isEqualTo("0.84");
    }
}
