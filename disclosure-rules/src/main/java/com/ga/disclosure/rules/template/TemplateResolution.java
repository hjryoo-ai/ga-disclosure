package com.ga.disclosure.rules.template;

import com.ga.disclosure.domain.vo.TemplateRef;
import tools.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 기준일에 해석된 서식. 필드 목록({@code order} 순)과 {@code layout}을 노출한다. {@code pendingConfirmation}은 필드가 아니며
 * 필수 판정({@link #requiredFieldCodes()})에 산입되지 않는다.
 */
public record TemplateResolution(TemplateRef ref, List<TemplateField> fields, JsonNode layout, List<String> pendingConfirmationRefs) {

    public TemplateResolution {
        Objects.requireNonNull(ref, "ref");
        fields = fields.stream().sorted(Comparator.comparingInt(TemplateField::order)).toList();
        layout = Objects.requireNonNull(layout, "layout").deepCopy();
        pendingConfirmationRefs = List.copyOf(pendingConfirmationRefs);
    }

    @Override
    public JsonNode layout() {
        return layout.deepCopy();
    }

    public List<TemplateField> requiredFields() {
        return fields.stream().filter(TemplateField::required).toList();
    }

    public Set<String> requiredFieldCodes() {
        Set<String> out = new LinkedHashSet<>();
        requiredFields().forEach(f -> out.add(f.code()));
        return out;
    }
}
