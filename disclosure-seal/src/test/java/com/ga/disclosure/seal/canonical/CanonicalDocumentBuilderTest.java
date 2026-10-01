package com.ga.disclosure.seal.canonical;

import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.vo.DisclosureId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 봉인 본문 빌더: 같은 입력 = 같은 바이트, 입력 하나가 바뀌면 해시가 바뀜, 수용심사 §3-3 구성, 저장 바이트에서 복원. */
class CanonicalDocumentBuilderTest {

    @Test
    void sameInputSameBytesAndHash() {
        CanonicalDocument a = CanonicalDocumentBuilder.build(SealFixtures.case02(), SealFixtures.NAME);
        CanonicalDocument b = CanonicalDocumentBuilder.build(SealFixtures.case02(), CustomerName.of("가상고객"));
        assertThat(a.bytes()).isEqualTo(b.bytes());
        assertThat(a.sha256()).isEqualTo(b.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void consultDateItemOrNameChangesTheHash() {
        String base = CanonicalDocumentBuilder.build(SealFixtures.case01(), SealFixtures.NAME).sha256();
        CanonicalInput in = SealFixtures.case01();
        CanonicalInput otherDate = new CanonicalInput(in.tenantId(), in.disclosureId(), in.version(), in.supersedesIdOrNull(), in.agentId(),
                in.customerRef(), LocalDate.of(2026, 9, 24), in.groupCode(), in.groupName(), in.issuerMode(), in.ruleVersionId(),
                in.tenantRuleVersionIdOrNull(), in.template(), in.snapshot(), in.panel(), in.items());
        assertThat(CanonicalDocumentBuilder.build(otherDate, SealFixtures.NAME).sha256()).isNotEqualTo(base);
        CanonicalInput otherItem = SealFixtures.input(in.disclosureId().value().toString(), java.util.List.of(in.items().get(0), in.items().get(1),
                SealFixtures.catalog(3, "INS-C:PRD-3120", true, SealFixtures.ok("MID", "보통", 3, 2, "1.02"),
                        SealFixtures.reasons("COVERAGE", "보장 범위"), "텍스트 추가")));
        assertThat(CanonicalDocumentBuilder.build(otherItem, SealFixtures.NAME).sha256()).isNotEqualTo(base);
        assertThat(CanonicalDocumentBuilder.build(in, CustomerName.of("다른고객")).sha256()).isNotEqualTo(base);
    }

    @Test
    void compositionFollowsTheAcceptanceReview() {
        JsonNode doc = CanonicalDocumentBuilder.build(SealFixtures.case02(), SealFixtures.NAME).json();
        assertThat(doc.propertyNames()).containsExactlyInAnyOrder("canonicalVersion", "tenantId", "disclosureId", "version", "supersedesId",
                "agentId", "customerRef", "customerName", "consultDate", "productGroup", "issuerMode", "pinned", "snapshot", "panel", "items");
        assertThat(doc.path("canonicalVersion").asInt()).isEqualTo(1);
        assertThat(doc.path("supersedesId").isNull()).isTrue();
        assertThat(doc.path("pinned").path("tenantRuleVersionId").isNull()).isTrue();
        assertThat(doc.path("snapshot").path("generatedAt").asString()).isEqualTo("2026-09-23T00:30:00Z");
        JsonNode temp = doc.path("items").get(1);
        assertThat(temp.path("productKey").isNull()).isTrue();
        assertThat(temp.path("grade").path("source").asString()).isEqualTo("LOCAL");
        assertThat(temp.path("fieldValues").path("PREMIUM").asInt()).isEqualTo(30100);
        assertThat(temp.path("fieldValues").propertyNames()).as("출처 없이 {코드: 값}").containsExactly("PREMIUM", "SURRENDER_VALUE_EXAMPLE");
        assertThat(doc.path("items").get(2).path("recommendation").isNull()).isTrue();
        assertThat(doc.path("items").get(0).path("recommendation").path("reasons").get(0).path("label").asString()).isEqualTo("보험료 수준");
    }

    @Test
    void storedBytesRoundTrip() {
        CanonicalDocument doc = CanonicalDocumentBuilder.build(SealFixtures.case03(), SealFixtures.NAME);
        CanonicalDocument restored = CanonicalDocument.parse(doc.bytes());
        assertThat(restored.bytes()).isEqualTo(doc.bytes());
        assertThat(restored.sha256()).isEqualTo(doc.sha256());
        byte[] pretty = doc.json().toPrettyString().getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CanonicalDocument.parse(pretty)).isInstanceOf(CanonicalSchemaViolation.class);
    }

    @Test
    void itemsMustBeNumberedInOrder() {
        CanonicalInput in = SealFixtures.case01();
        CanonicalInput swapped = SealFixtures.input(in.disclosureId().value().toString(),
                java.util.List.of(in.items().get(1), in.items().get(0), in.items().get(2)));
        assertThatThrownBy(() -> CanonicalDocumentBuilder.build(swapped, SealFixtures.NAME)).hasMessageContaining("1..n");
    }

    @Test
    void toStringDoesNotCarryTheBody() {
        CanonicalDocument doc = CanonicalDocumentBuilder.build(SealFixtures.case01(), SealFixtures.NAME);
        assertThat(doc.toString()).doesNotContain("가상고객").contains(doc.sha256());
        assertThat(DisclosureId.of(UUID.fromString("00000000-0000-4000-8000-000000000001")).toString()).isNotBlank();
    }
}
