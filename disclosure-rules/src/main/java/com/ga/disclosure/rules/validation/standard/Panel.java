package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.List;

/** 모든 보험사가 상담일 기준 추천가능 보험사(위탁 패널)다. */
final class Panel implements Validation {

    static final String ID = "R-PANEL";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        List<InsurerCode> offPanel = subject.items().stream()
                .map(ValidationSubject.Item::insurerCode)
                .distinct()
                .filter(insurer -> !subject.isInsurerOnPanel(insurer, subject.consultDate()))
                .toList();
        return offPanel.isEmpty()
                ? ValidationResult.pass(ID, "모든 보험사가 " + subject.consultDate() + " 기준 패널에 있다")
                : ValidationResult.fail(ID, subject.consultDate() + " 기준 패널에 없는 보험사: " + offPanel);
    }
}
