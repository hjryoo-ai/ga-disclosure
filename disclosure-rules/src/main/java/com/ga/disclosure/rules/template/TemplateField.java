package com.ga.disclosure.rules.template;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 서식 항목 1건({@code form_template.fields[]}). 코드·라벨·필수 여부·배치는 전부 서식 데이터다 — 코드에 항목명을 두지 않는다.
 *
 * @param render 렌더 속성 원문({@code scope}, {@code readOnly}, {@code unavailableText} …)
 */
public record TemplateField(String code, String label, boolean required, FieldSource source, int order, FieldScope scope,
                            JsonNode render) {

    public TemplateField {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(scope, "scope");
        render = Objects.requireNonNull(render, "render").deepCopy();
    }

    @Override
    public JsonNode render() {
        return render.deepCopy();
    }
}
