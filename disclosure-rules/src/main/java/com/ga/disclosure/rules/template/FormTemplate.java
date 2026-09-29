package com.ga.disclosure.rules.template;

import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.vo.TemplateRef;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 서식 템플릿 1건(설계서 §5 {@code form_template}). 번들 출처면 {@code sourceBundleId}·{@code bundleHash}가 있고,
 * {@code bundleHash = SHA-256(JCS(body()))}이다.
 *
 * @param pendingConfirmation 정본 확인 전 자리표시({@code [{ref, note}]}). 필드가 아니다.
 */
public record FormTemplate(
        TemplateRef ref,
        TemplateType templateType,
        LocalDate applyFrom,
        LocalDate applyTo,
        JsonNode fields,
        JsonNode layout,
        JsonNode pendingConfirmation,
        String sourceBundleId,
        String bundleHash) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public FormTemplate {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(templateType, "templateType");
        Objects.requireNonNull(applyFrom, "applyFrom");
        fields = Objects.requireNonNull(fields, "fields").deepCopy();
        layout = Objects.requireNonNull(layout, "layout").deepCopy();
        pendingConfirmation = Objects.requireNonNull(pendingConfirmation, "pendingConfirmation").deepCopy();
        if (applyTo != null && !applyTo.isAfter(applyFrom)) {
            throw new IllegalArgumentException("applyTo must be after applyFrom: " + ref);
        }
        if ((sourceBundleId == null) != (bundleHash == null)) {
            throw new IllegalArgumentException("bundle provenance needs both id and hash: " + ref);
        }
    }

    @Override
    public JsonNode fields() {
        return fields.deepCopy();
    }

    @Override
    public JsonNode layout() {
        return layout.deepCopy();
    }

    @Override
    public JsonNode pendingConfirmation() {
        return pendingConfirmation.deepCopy();
    }

    /** 번들 본문 형태({@code {fields, layout, pendingConfirmation}}) — 해시 대사의 입력. */
    public JsonNode body() {
        ObjectNode body = JSON.createObjectNode();
        body.set("fields", fields());
        body.set("layout", layout());
        body.set("pendingConfirmation", pendingConfirmation());
        return body;
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(applyFrom) && (applyTo == null || date.isBefore(applyTo));
    }
}
