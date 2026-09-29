package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

/** 비교 항목 수 ≥ 룰 {@code minCompare}. 고객 요청 항목도 산입한다(R-REQUESTED). */
final class MinCompare implements Validation {

    static final String ID = "R-MIN-COMPARE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        int count = subject.items().size();
        int min = rule.minCompare();
        return count >= min
                ? ValidationResult.pass(ID, "비교 상품 " + count + "개 (최소 " + min + ")")
                : ValidationResult.fail(ID, "비교 상품이 " + count + "개로 최소 " + min + "개에 못 미친다");
    }
}
