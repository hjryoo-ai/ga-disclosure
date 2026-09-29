package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.List;

/** 모든 비교 항목이 헤더의 유사상품군에 속한다. */
final class SameGroup implements Validation {

    static final String ID = "R-SAME-GROUP";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        List<ProductKey> outside = subject.items().stream()
                .filter(i -> !i.groupCode().equals(subject.groupCode()))
                .map(ValidationSubject.Item::productKey)
                .toList();
        return outside.isEmpty()
                ? ValidationResult.pass(ID, "모든 항목이 상품군 " + subject.groupCode() + "에 속한다")
                : ValidationResult.fail(ID, "상품군 " + subject.groupCode() + " 밖의 항목: " + outside);
    }
}
