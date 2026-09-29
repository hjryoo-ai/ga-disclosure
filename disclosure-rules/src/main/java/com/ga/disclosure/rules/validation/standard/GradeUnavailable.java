package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.List;

/**
 * UNAVAILABLE(산출불가) 항목이 있으면 "산출불가·사유" 표기 + 관리자 확인 + 플래그가 필요하다 — 오버라이드 경로로만 넘어간다.
 * 사유가 없으면 표기 자체가 불가능하므로 오버라이드도 불가.
 */
final class GradeUnavailable implements Validation {

    static final String ID = "R-GRADE-UNAVAILABLE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        List<GradeSnapshotItem> unavailable = subject.gradeSnapshot()
                .map(s -> s.items().stream().filter(i -> !i.isAvailable()).toList())
                .orElse(List.of());
        if (unavailable.isEmpty()) {
            return ValidationResult.pass(ID, "산출불가 항목이 없다");
        }
        if (unavailable.stream().anyMatch(i -> i.unavailableReason() == null || i.unavailableReason().isBlank())) {
            return ValidationResult.fail(ID, "산출불가 사유가 없는 항목이 있어 표기할 수 없다");
        }
        return ValidationResult.failOverridable(ID, "산출불가 항목 " + unavailable.stream()
                .map(i -> i.productKey() + "(" + i.unavailableReason() + ")").toList() + " — 관리자 확인과 준법 플래그가 필요하다");
    }
}
