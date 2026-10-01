package com.ga.disclosure.rules.template;

import com.ga.disclosure.rules.bundle.RuleSchemas;
import com.ga.disclosure.rules.testing.Bundles;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** 3B: 서식 본문의 결속·식별부·배치 구조. 스키마가 막는 것과 구조 검사가 막는 것을 STANDARD-v1 본문 변형으로 확인한다. */
class TemplateLayoutCheckTest {

    private static ObjectNode body() {
        return (ObjectNode) Bundles.template(Bundles.STANDARD_V1).body().deepCopy();
    }

    private static ObjectNode field(ObjectNode body, int index) {
        return (ObjectNode) body.path("fields").get(index);
    }

    private static ArrayNode sectionFields(ObjectNode body, String code) {
        for (JsonNode s : body.path("layout").path("sections")) {
            if (s.path("code").asString().equals(code)) {
                return (ArrayNode) s.path("fields");
            }
        }
        throw new IllegalArgumentException(code);
    }

    @Test
    void theStandardTemplateIsStructurallySound() {
        assertThat(RuleSchemas.validateTemplateBody(body())).isEmpty();
        assertThat(TemplateLayoutCheck.problems(body())).isEmpty();
    }

    @Test
    void schemaBindsScopeSourceAndSection() {
        ObjectNode wrongScope = body();
        ((ObjectNode) field(wrongScope, 0).path("render")).put("scope", "PER_ITEM");
        assertThat(RuleSchemas.validateTemplateBody(wrongScope)).isNotEmpty();
        ObjectNode wrongSection = body();
        field(wrongSection, 0).put("section", "COMPARISON");
        assertThat(RuleSchemas.validateTemplateBody(wrongSection)).isNotEmpty();
        ObjectNode unknownBind = body();
        ((ObjectNode) field(unknownBind, 4).path("render")).put("bind", "PREMIUM");
        assertThat(RuleSchemas.validateTemplateBody(unknownBind)).isNotEmpty();
        ObjectNode noBind = body();
        ((ObjectNode) field(noBind, 4).path("render")).remove("bind");
        assertThat(RuleSchemas.validateTemplateBody(noBind)).isNotEmpty();
        ObjectNode noTitle = body();
        ((ObjectNode) noTitle.path("layout")).remove("title");
        assertThat(RuleSchemas.validateTemplateBody(noTitle)).isNotEmpty();
        ObjectNode badLabelRef = body();
        field(badLabelRef, 0).put("labelRef", "확인 필요");
        assertThat(RuleSchemas.validateTemplateBody(badLabelRef)).isNotEmpty();
        // 엔진 결속은 산출불가 문구가 있어야 한다
        ObjectNode engineWithoutText = body();
        for (JsonNode f : engineWithoutText.path("fields")) {
            if (f.path("render").path("bind").asString().startsWith("ENGINE_")) {
                ((ObjectNode) f.path("render")).remove("unavailableText");
            }
        }
        assertThat(RuleSchemas.validateTemplateBody(engineWithoutText)).isNotEmpty();
    }

    @Test
    void everyFieldSitsInExactlyOneMatchingSection() {
        ObjectNode unplaced = body();
        ArrayNode header = sectionFields(unplaced, "HEADER");
        header.remove(header.size() - 1);
        assertThat(TemplateLayoutCheck.problems(unplaced)).anyMatch(p -> p.contains("not placed"));

        ObjectNode twice = body();
        sectionFields(twice, "COMPARISON").add(sectionFields(twice, "COMPARISON").get(0).asString());
        assertThat(RuleSchemas.validateTemplateBody(twice)).as("섹션 안의 중복은 스키마(uniqueItems)").isNotEmpty();
        ObjectNode twoSections = body();
        sectionFields(twoSections, "SUMMARY").add(sectionFields(twoSections, "COMPARISON").get(0).asString());
        assertThat(TemplateLayoutCheck.problems(twoSections)).anyMatch(p -> p.contains("more than one"));

        ObjectNode unknown = body();
        sectionFields(unknown, "SUMMARY").add("NOT_A_FIELD");
        assertThat(TemplateLayoutCheck.problems(unknown)).anyMatch(p -> p.contains("unknown field"));

        ObjectNode wrongOrientation = body();
        String perItem = sectionFields(wrongOrientation, "COMPARISON").remove(0).asString();
        sectionFields(wrongOrientation, "SUMMARY").add(perItem);
        assertThat(TemplateLayoutCheck.problems(wrongOrientation)).anyMatch(p -> p.contains("cannot sit in DOCUMENT"));

        ObjectNode mixed = body();
        String summaryField = sectionFields(mixed, "SUMMARY").remove(0).asString();
        sectionFields(mixed, "HEADER").add(summaryField);
        assertThat(TemplateLayoutCheck.problems(mixed)).anyMatch(p -> p.contains("mixes identification"));
    }
}
