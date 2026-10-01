package com.ga.disclosure.rules.template;

import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * 서식 항목 1건({@code form_template.fields[]}). 코드·라벨·필수 여부·배치·결속은 전부 서식 데이터다 — 코드에 항목명을 두지 않는다.
 * 결속({@link Bind})의 허용 범위·출처, 식별부 구분({@link FieldSection})과 결속의 짝은 서식 스키마와 이 생성자가 함께 강제한다.
 *
 * @param render      렌더 속성 원문({@code scope}, {@code bind}, {@code readOnly}, {@code unavailableText} …)
 * @param labelRefOrNull 라벨이 정본 확인 전 가정이면 그 참조(예: {@code TODO(confirm#2)}) — 3B 계획 승인 Q2
 */
public record TemplateField(String code, String label, boolean required, FieldSource source, int order, FieldScope scope, Bind bind,
                            FieldSection section, String labelRefOrNull, JsonNode render) {

    public TemplateField {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(bind, "bind");
        Objects.requireNonNull(section, "section");
        render = Objects.requireNonNull(render, "render").deepCopy();
        if (bind.scope() != scope || bind.source() != source) {
            throw new IllegalArgumentException(code + ": bind " + bind + " requires scope " + bind.scope() + " and source " + bind.source());
        }
        if ((section == FieldSection.HEADER) != bind.identification()) {
            throw new IllegalArgumentException(code + ": section HEADER is exactly the identification bindings (got " + section + "/" + bind + ")");
        }
        if (bind.needsUnavailableText() && !render.path("unavailableText").isString()) {
            throw new IllegalArgumentException(code + ": bind " + bind + " needs render.unavailableText");
        }
    }

    @Override
    public JsonNode render() {
        return render.deepCopy();
    }

    public Optional<String> labelRef() {
        return Optional.ofNullable(labelRefOrNull);
    }
}
