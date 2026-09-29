package com.ga.disclosure.rules.testing;

import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.rules.template.FormTemplate;
import com.ga.disclosure.rules.template.FormTemplatePort;
import com.ga.platform.core.tenant.TenantId;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 테스트 픽스처: 인메모리 서식 포트(겹침 제약 없음). */
public final class InMemoryFormTemplatePort implements FormTemplatePort {

    private final List<FormTemplate> templates = new ArrayList<>();

    public InMemoryFormTemplatePort add(FormTemplate template) {
        templates.add(template);
        return this;
    }

    @Override
    public List<FormTemplate> findActive(TenantId tenant, TemplateType templateType, LocalDate asOf) {
        return templates.stream().filter(t -> t.templateType() == templateType && t.covers(asOf)).toList();
    }
}
