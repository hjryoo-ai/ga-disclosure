package com.ga.disclosure.rules.template;

import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.vo.TemplateRef;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 기준일에 해석된 서식. 필드 목록({@code order} 순)과 {@code layout}을 노출한다. {@code pendingConfirmation}은 필드가 아니며
 * 필수 판정({@link #requiredFieldCodes()})에 산입되지 않는다.
 */
public record TemplateResolution(TemplateRef ref, TemplateType templateType, List<TemplateField> fields, JsonNode layout,
                                 List<String> pendingConfirmationRefs) {

    public TemplateResolution {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(templateType, "templateType");
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

    public Optional<TemplateField> field(String code) {
        return fields.stream().filter(f -> f.code().equals(code)).findFirst();
    }

    /** 문서 제목({@code layout.title}, 서식 데이터 — 렌더러 리터럴이 아니다). */
    public String title() {
        return layout.path("title").asString();
    }

    /** 배치 섹션(데이터 순서). 섹션이 가리키는 항목 코드는 전부 이 서식의 항목이다(스키마·로더가 보장, 여기서도 확인). */
    public List<LayoutSection> sections() {
        List<LayoutSection> out = new ArrayList<>();
        for (JsonNode s : layout.path("sections")) {
            List<String> codes = new ArrayList<>();
            s.path("fields").forEach(c -> codes.add(c.asString()));
            for (String c : codes) {
                if (field(c).isEmpty()) {
                    throw new IllegalStateException("layout section " + s.path("code").asString() + " names unknown field " + c);
                }
            }
            JsonNode label = s.path("label");
            out.add(new LayoutSection(s.path("code").asString(), LayoutSection.Orientation.valueOf(s.path("orientation").asString()),
                    label.isString() ? label.asString() : null, s.path("columnsPerTable").asInt(0), codes));
        }
        return List.copyOf(out);
    }
}
