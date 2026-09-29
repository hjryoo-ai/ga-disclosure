package com.ga.disclosure.rules.bundle;

import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.vo.TemplateRef;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 표준 서식 번들. body = {@code {fields, layout, pendingConfirmation}}.
 *
 * @param supersedes 이 서식이 대체하는 선행 서식(없으면 {@code null})
 */
public record TemplateBundle(
        String bundleId,
        TemplateRef template,
        TemplateType templateType,
        LocalDate applyFrom,
        LocalDate applyTo,
        TemplateRef supersedes,
        JsonNode body,
        String bodyHash) implements Bundle {

    public TemplateBundle {
        Objects.requireNonNull(bundleId, "bundleId");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(templateType, "templateType");
        Objects.requireNonNull(applyFrom, "applyFrom");
        Objects.requireNonNull(bodyHash, "bodyHash");
        body = Objects.requireNonNull(body, "body").deepCopy();
    }

    @Override
    public JsonNode body() {
        return body.deepCopy();
    }

    @Override
    public BundleKind kind() {
        return BundleKind.TEMPLATE;
    }
}
