package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** 룰 {@code distinctInsurer=true}이면 비교 항목의 보험사가 서로 다르다. */
final class DistinctInsurer implements Validation {

    static final String ID = "R-DISTINCT-INSURER";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        if (!rule.distinctInsurer()) {
            return ValidationResult.pass(ID, "룰이 보험사 상이를 요구하지 않는다");
        }
        Set<InsurerCode> seen = new HashSet<>();
        Set<InsurerCode> duplicated = new LinkedHashSet<>();
        subject.items().forEach(i -> {
            if (!seen.add(i.insurerCode())) {
                duplicated.add(i.insurerCode());
            }
        });
        return duplicated.isEmpty()
                ? ValidationResult.pass(ID, "보험사 " + seen.size() + "곳이 서로 다르다")
                : ValidationResult.fail(ID, "같은 보험사 상품이 둘 이상이다: " + duplicated);
    }
}
