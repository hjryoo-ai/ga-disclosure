package com.ga.disclosure.rules.template;

import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.rules.resolve.ResolutionFailure;
import com.ga.disclosure.rules.resolve.RuleResolutionException;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 기준일 서식 해석(단건, 0건 {@code NO_TEMPLATE}, 2건 이상 {@code AMBIGUOUS}). */
public final class TemplateResolver {

    private final FormTemplatePort port;

    public TemplateResolver(FormTemplatePort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    public TemplateResolution resolve(TenantId tenant, TemplateType templateType, LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf is mandatory (consult date)");
        List<FormTemplate> found = port.findActive(tenant, templateType, asOf);
        for (FormTemplate t : found) {
            if (t.templateType() != templateType || !t.covers(asOf)) {
                throw new IllegalStateException("port returned " + t.ref() + " for " + templateType + " on " + asOf);
            }
        }
        if (found.isEmpty()) {
            throw new RuleResolutionException(ResolutionFailure.NO_TEMPLATE, "no " + templateType + " template on " + asOf + " for " + tenant);
        }
        if (found.size() > 1) {
            throw new RuleResolutionException(ResolutionFailure.AMBIGUOUS,
                    found.size() + " " + templateType + " templates on " + asOf + ": " + found.stream().map(FormTemplate::ref).toList());
        }
        return resolution(found.getFirst());
    }

    /** 서식 레코드를 해석 결과로 바꾼다(필드 형식은 서식 스키마가 보장). */
    public static TemplateResolution resolution(FormTemplate template) {
        List<TemplateField> fields = new ArrayList<>();
        for (JsonNode f : template.fields()) {
            JsonNode render = f.path("render");
            fields.add(new TemplateField(
                    f.path("code").asString(),
                    f.path("label").asString(),
                    f.path("required").asBoolean(),
                    FieldSource.valueOf(f.path("source").asString()),
                    f.path("order").asInt(),
                    FieldScope.valueOf(render.path("scope").asString()),
                    render));
        }
        List<String> pending = new ArrayList<>();
        template.pendingConfirmation().forEach(p -> pending.add(p.path("ref").asString()));
        return new TemplateResolution(template.ref(), fields, template.layout(), pending);
    }
}
