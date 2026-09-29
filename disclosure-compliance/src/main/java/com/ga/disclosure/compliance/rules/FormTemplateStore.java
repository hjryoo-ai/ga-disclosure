package com.ga.disclosure.compliance.rules;

import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.rules.template.FormTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 서식 템플릿 저장 포트(infra 어댑터가 구현, 바인딩된 테넌트 범위). */
public interface FormTemplateStore {

    Optional<FormTemplate> find(TemplateRef ref);

    void insert(FormTemplate template);

    /** {@code apply_to}가 NULL일 때만 쓴다. 갱신 행 수. */
    int closeApplyTo(TemplateRef ref, LocalDate applyTo);

    /** 번들 출처 서식 전부. */
    List<FormTemplate> findBundled();
}
