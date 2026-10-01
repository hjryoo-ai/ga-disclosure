package com.ga.disclosure.rules.template;

import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.testing.Snapshots;
import com.ga.disclosure.rules.testing.TestItem;
import com.ga.disclosure.rules.testing.TestSubject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3B §1: 결속 어휘의 해석 규칙. 서식 항목은 STANDARD-v1 번들 데이터에서 결속으로 찾는다(코드 이름을 테스트에 두지 않는다 —
 * {@code AGENT_INPUT}만 정본 서식에 없으므로 합성 항목을 쓴다).
 */
class BindingResolverTest {

    private static final LocalDate CONSULT = LocalDate.parse("2026-09-23");
    private static final TemplateResolution TEMPLATE =
            TemplateResolver.resolution(Bundles.template(Bundles.template(Bundles.STANDARD_V1), null));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static TemplateField bound(Bind bind) {
        return TEMPLATE.fields().stream().filter(f -> f.bind() == bind).findFirst().orElseThrow();
    }

    private static TemplateField synthetic(Bind bind, String code) {
        ObjectNode render = JSON.createObjectNode().put("scope", bind.scope().name()).put("bind", bind.name());
        if (bind.needsUnavailableText()) {
            render.put("unavailableText", "산출불가");
        }
        return new TemplateField(code, "합성 " + code, true, bind.source(), 99, bind.scope(), bind,
                bind.identification() ? FieldSection.HEADER : FieldSection.COMPARISON, null, render);
    }

    private static TestSubject subject(TestItem... items) {
        return TestSubject.of("PG-HEALTH", CONSULT, List.of(items)).withSnapshot(Snapshots.snapshot(TieBreak.SHARED_RANK,
                Snapshots.ok("INS-A:PRD-1", 2, 1, false), Snapshots.unavailable("INS-B:PRD-2", "NO_RATE_DATA")));
    }

    @Test
    void identificationBindingsArePendingUntilSealing() {
        BindingView doc = subject(TestItem.of("INS-A", "PRD-1", "PG-HEALTH")).bindings();
        assertThat(BindingResolver.resolve(bound(Bind.HEADER_DISCLOSURE_NO), doc)).containsInstanceOf(Bound.Pending.class);
        assertThat(BindingResolver.resolve(bound(Bind.HEADER_CUSTOMER_NAME), doc)).containsInstanceOf(Bound.Pending.class);
        assertThat(BindingResolver.present(bound(Bind.HEADER_DISCLOSURE_NO), doc)).isTrue();
        assertThat(value(BindingResolver.resolve(bound(Bind.HEADER_CONSULT_DATE), doc))).isEqualTo("\"2026-09-23\"");
        assertThat(value(BindingResolver.resolve(bound(Bind.HEADER_AGENT), doc))).isEqualTo("\"AGENT-T\"");
        assertThat(value(BindingResolver.resolve(bound(Bind.HEADER_PRODUCT_GROUP), doc))).isEqualTo("\"상품군 PG-HEALTH\"");
        assertThat(value(BindingResolver.resolve(bound(Bind.PANEL_INSURERS), doc))).isEqualTo("[\"INS-A\"]");
    }

    @Test
    void documentBindingsAreAbsentWithoutTheirFacts() {
        TestSubject s = subject(TestItem.of("INS-A", "PRD-1", "PG-HEALTH"));
        assertThat(BindingResolver.present(bound(Bind.HEADER_PRODUCT_GROUP), s.withoutProductGroupName().bindings())).isFalse();
        assertThat(BindingResolver.present(bound(Bind.PANEL_INSURERS), s.withPanel(Set.of()).bindings())).isFalse();
    }

    @Test
    void itemBindingsFollowTheStructure() {
        TestSubject s = subject(TestItem.of("INS-A", "PRD-1", "PG-HEALTH").recommended("PREMIUM").text("보장 비교"),
                TestItem.of("INS-B", "PRD-2", "PG-HEALTH")).withPanel(Set.of(InsurerCode.of("INS-A")));
        BindingView.Item a = s.bindings().items().get(0);
        BindingView.Item b = s.bindings().items().get(1);
        assertThat(value(BindingResolver.resolve(bound(Bind.ITEM_INSURER_NAME), a))).isEqualTo("\"INS-A\"");
        assertThat(BindingResolver.present(bound(Bind.ITEM_INSURER_NAME), b)).as("패널 밖 보험사").isFalse();
        assertThat(value(BindingResolver.resolve(bound(Bind.ITEM_PRODUCT_NAME), a))).isEqualTo("\"상품 PRD-1\"");
        // 엔진: OK는 라벨·순위, 산출불가는 서식 문구
        assertThat(value(BindingResolver.resolve(bound(Bind.ENGINE_GRADE_LABEL), a))).isEqualTo("\"등급2\"");
        assertThat(value(BindingResolver.resolve(bound(Bind.ENGINE_RANK), a))).isEqualTo("1");
        String unavailable = bound(Bind.ENGINE_GRADE_LABEL).render().path("unavailableText").asString();
        assertThat(value(BindingResolver.resolve(bound(Bind.ENGINE_GRADE_LABEL), b))).isEqualTo("\"" + unavailable + "\"");
        TemplateField reasonText = synthetic(Bind.ENGINE_UNAVAILABLE_TEXT, "SYN_UNAVAILABLE");
        assertThat(BindingResolver.resolve(reasonText, a)).containsInstanceOf(Bound.Blank.class);
        assertThat(value(BindingResolver.resolve(reasonText, b))).isEqualTo("[\"산출불가\",\"NO_RATE_DATA\"]");
        // 추천사유: 추천 항목은 라벨들 + 텍스트, 비추천 항목은 빈 칸(그 판단이 값)
        assertThat(value(BindingResolver.resolve(bound(Bind.RECOMMENDATION), a))).isEqualTo("[\"PREMIUM\",\"보장 비교\"]");
        assertThat(BindingResolver.resolve(bound(Bind.RECOMMENDATION), b)).containsInstanceOf(Bound.Blank.class);
        // 산출 전에는 엔진 결속이 없다
        assertThat(BindingResolver.present(bound(Bind.ENGINE_RANK), s.withSnapshot(null).bindings().items().get(0))).isFalse();
    }

    @Test
    void recommendedItemWithoutReasonsHasNoRecommendationValue() {
        BindingView.Item a = subject(TestItem.of("INS-A", "PRD-1", "PG-HEALTH").recommended()).bindings().items().get(0);
        assertThat(BindingResolver.present(bound(Bind.RECOMMENDATION), a)).isFalse();
    }

    @Test
    void storedValueBindingsReadFieldValuesByTheTemplateCode() {
        TemplateField premium = bound(Bind.CATALOG_DEFAULT);
        BindingView.Item filled = subject(TestItem.of("INS-A", "PRD-1", "PG-HEALTH").field(premium.code(), "32100")).bindings().items().get(0);
        BindingView.Item blank = subject(TestItem.of("INS-A", "PRD-1", "PG-HEALTH").field(premium.code(), "  ")).bindings().items().get(0);
        BindingView.Item agentEntered = subject(TestItem.of("INS-A", "PRD-1", "PG-HEALTH").agentField(premium.code(), "1")).bindings()
                .items().get(0);
        assertThat(value(BindingResolver.resolve(premium, filled))).isEqualTo("\"32100\"");
        assertThat(BindingResolver.present(premium, blank)).isFalse();
        assertThat(BindingResolver.present(premium, agentEntered)).as("카탈로그 기본값 결속은 출처를 묻지 않는다").isTrue();
    }

    /** S13(단위): 설계사 입력 결속은 설계사가 입력한 값만 존재로 본다 — 추천사유나 카탈로그 값으로 대체되지 않는다. */
    @Test
    void agentInputIsNotSatisfiedByRecommendationOrCatalogValues() {
        TemplateField agentInput = synthetic(Bind.AGENT_INPUT, "SYN_AGENT");
        BindingView.Item reasonedOnly = subject(TestItem.of("INS-A", "PRD-1", "PG-HEALTH").recommended("PREMIUM")).bindings().items().get(0);
        BindingView.Item catalogValue = subject(TestItem.of("INS-A", "PRD-1", "PG-HEALTH").recommended("PREMIUM").field("SYN_AGENT", "x"))
                .bindings().items().get(0);
        BindingView.Item entered = subject(TestItem.of("INS-A", "PRD-1", "PG-HEALTH").agentField("SYN_AGENT", "x")).bindings().items().get(0);
        assertThat(BindingResolver.present(agentInput, reasonedOnly)).isFalse();
        assertThat(BindingResolver.present(agentInput, catalogValue)).isFalse();
        assertThat(BindingResolver.present(agentInput, entered)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Bind.class)
    void everyBindingResolvesOnlyAtItsOwnScope(Bind bind) {
        TestSubject s = subject(TestItem.of("INS-A", "PRD-1", "PG-HEALTH"));
        TemplateField f = synthetic(bind, "SYN_" + bind.ordinal());
        if (bind.scope() == FieldScope.PER_DOCUMENT) {
            assertThatThrownBy(() -> BindingResolver.resolve(f, s.bindings().items().get(0))).isInstanceOf(IllegalArgumentException.class);
            BindingResolver.resolve(f, s.bindings());
        } else {
            assertThatThrownBy(() -> BindingResolver.resolve(f, s.bindings())).isInstanceOf(IllegalArgumentException.class);
            BindingResolver.resolve(f, s.bindings().items().get(0));
        }
    }

    @Test
    void fieldDeclarationMustMatchItsBinding() {
        ObjectNode render = JSON.createObjectNode().put("scope", "PER_ITEM").put("bind", "CATALOG_DEFAULT");
        assertThatThrownBy(() -> new TemplateField("X", "x", true, FieldSource.AGENT, 1, FieldScope.PER_ITEM, Bind.CATALOG_DEFAULT,
                FieldSection.COMPARISON, null, render)).hasMessageContaining("requires scope");
        assertThatThrownBy(() -> new TemplateField("X", "x", true, FieldSource.CATALOG, 1, FieldScope.PER_ITEM, Bind.CATALOG_DEFAULT,
                FieldSection.HEADER, null, render)).hasMessageContaining("section HEADER");
        ObjectNode header = JSON.createObjectNode().put("scope", "PER_DOCUMENT").put("bind", "HEADER_AGENT");
        assertThatThrownBy(() -> new TemplateField("X", "x", true, FieldSource.SYSTEM, 1, FieldScope.PER_DOCUMENT, Bind.HEADER_AGENT,
                FieldSection.COMPARISON, null, header)).hasMessageContaining("section HEADER");
        ObjectNode engine = JSON.createObjectNode().put("scope", "PER_ITEM").put("bind", "ENGINE_RANK");
        assertThatThrownBy(() -> new TemplateField("X", "x", true, FieldSource.ENGINE, 1, FieldScope.PER_ITEM, Bind.ENGINE_RANK,
                FieldSection.COMPARISON, null, engine)).hasMessageContaining("unavailableText");
    }

    private static String value(Optional<Bound> bound) {
        assertThat(bound).containsInstanceOf(Bound.Value.class);
        return ((Bound.Value) bound.orElseThrow()).value().toString();
    }
}
