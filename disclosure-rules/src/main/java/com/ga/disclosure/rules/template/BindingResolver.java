package com.ga.disclosure.rules.template;

import com.ga.disclosure.domain.disclosure.ItemGrade;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 서식 항목의 결속({@link Bind})이 가리키는 값을 찾는 유일한 함수(3B 계획 §1). 렌더러는 {@link #resolve}의 {@link Bound.Value}를 인쇄하고,
 * R-FIELD-REQUIRED는 {@link #present}로 존재를 판정한다 — 두 판단이 갈라질 수 없다. 항목 코드는 {@link Bind#CATALOG_DEFAULT}·
 * {@link Bind#AGENT_INPUT}에서 값의 키로만 쓴다(코드에 항목명을 두지 않는다, 절대 규칙 4).
 */
public final class BindingResolver {

    private static final JsonNodeFactory NODES = JsonMapper.builder().build().getNodeFactory();

    private BindingResolver() {
    }

    /** 확인서당 한 값인 결속. */
    public static Optional<Bound> resolve(TemplateField field, BindingView document) {
        if (field.bind().scope() != FieldScope.PER_DOCUMENT) {
            throw new IllegalArgumentException(field.code() + " (" + field.bind() + ") is bound per item");
        }
        Objects.requireNonNull(document, "document");
        return switch (field.bind()) {
            case HEADER_DISCLOSURE_NO -> Optional.of(document.disclosureNo().map(BindingResolver::text).orElseGet(Bound.Pending::new));
            case HEADER_CONSULT_DATE -> Optional.of(text(document.consultDate().toString()));
            case HEADER_AGENT -> value(document.agentId());
            case HEADER_CUSTOMER_NAME -> Optional.of(document.customerName().map(BindingResolver::text).orElseGet(Bound.Pending::new));
            case HEADER_PRODUCT_GROUP -> document.productGroupName().flatMap(BindingResolver::value);
            case PANEL_INSURERS -> list(document.panelInsurerNames());
            default -> throw new IllegalStateException(field.bind() + " is not a document binding");
        };
    }

    /** 항목(열)마다 한 값인 결속. */
    public static Optional<Bound> resolve(TemplateField field, BindingView.Item item) {
        if (field.bind().scope() != FieldScope.PER_ITEM) {
            throw new IllegalArgumentException(field.code() + " (" + field.bind() + ") is bound per document");
        }
        Objects.requireNonNull(item, "item");
        return switch (field.bind()) {
            case ITEM_INSURER_NAME -> item.insurerName().flatMap(BindingResolver::value);
            case ITEM_PRODUCT_NAME -> value(item.productName());
            case CATALOG_DEFAULT -> item.fieldValue(field.code()).flatMap(BindingResolver::json);
            case AGENT_INPUT -> item.enteredByAgent(field.code()) ? item.fieldValue(field.code()).flatMap(BindingResolver::json) : Optional.empty();
            case ENGINE_GRADE_LABEL -> item.grade().flatMap(g -> switch (g) {
                case ItemGrade.Ok ok -> value(ok.gradeLabel());
                case ItemGrade.Unavailable u -> unavailableText(field);
            });
            case ENGINE_RANK -> item.grade().flatMap(g -> switch (g) {
                case ItemGrade.Ok ok -> Optional.of(new Bound.Value(NODES.numberNode(ok.rankInSet())));
                case ItemGrade.Unavailable u -> unavailableText(field);
            });
            case ENGINE_UNAVAILABLE_TEXT -> item.grade().flatMap(g -> switch (g) {
                case ItemGrade.Ok ok -> Optional.of(new Bound.Blank());
                case ItemGrade.Unavailable u -> unavailableText(field).map(t -> {
                    ArrayNode lines = NODES.arrayNode().add(((Bound.Value) t).value()).add(u.reason());
                    return new Bound.Value(lines);
                });
            });
            case RECOMMENDATION -> recommendation(item);
            default -> throw new IllegalStateException(field.bind() + " is not an item binding");
        };
    }

    public static boolean present(TemplateField field, BindingView document) {
        return resolve(field, document).isPresent();
    }

    public static boolean present(TemplateField field, BindingView.Item item) {
        return resolve(field, item).isPresent();
    }

    /** 추천 항목: 사유 라벨들 뒤에 텍스트(있으면). 사유가 없으면 없음. 비추천 항목: 빈 칸. */
    private static Optional<Bound> recommendation(BindingView.Item item) {
        if (!item.recommended()) {
            return Optional.of(new Bound.Blank());
        }
        List<String> labels = item.reasonLabels();
        if (labels.isEmpty()) {
            return Optional.empty();
        }
        ArrayNode lines = NODES.arrayNode();
        labels.forEach(lines::add);
        item.reasonText().filter(t -> !t.isBlank()).ifPresent(lines::add);
        return Optional.of(new Bound.Value(lines));
    }

    private static Optional<Bound> unavailableText(TemplateField field) {
        JsonNode text = field.render().path("unavailableText");
        return text.isString() ? value(text.asString()) : Optional.empty();
    }

    private static Optional<Bound> list(List<String> names) {
        if (names.isEmpty()) {
            return Optional.empty();
        }
        ArrayNode out = NODES.arrayNode();
        names.forEach(out::add);
        return Optional.of(new Bound.Value(out));
    }

    private static Optional<Bound> value(String s) {
        return s == null || s.isBlank() ? Optional.empty() : Optional.of(text(s));
    }

    private static Bound text(String s) {
        return new Bound.Value(NODES.stringNode(s));
    }

    private static Optional<Bound> json(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || (node.isString() && node.asString().isBlank())) {
            return Optional.empty();
        }
        return Optional.of(new Bound.Value(node.deepCopy()));
    }
}
