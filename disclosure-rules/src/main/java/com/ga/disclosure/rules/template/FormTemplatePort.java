package com.ga.disclosure.rules.template;

import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.platform.core.tenant.TenantId;

import java.time.LocalDate;
import java.util.List;

/** 서식 조회 포트(infra 어댑터가 구현). 기준일에 적용 구간이 걸친 서식을 전부 돌려준다 — 단건 판정은 해석기의 몫이다. */
public interface FormTemplatePort {

    List<FormTemplate> findActive(TenantId tenant, TemplateType templateType, LocalDate asOf);
}
