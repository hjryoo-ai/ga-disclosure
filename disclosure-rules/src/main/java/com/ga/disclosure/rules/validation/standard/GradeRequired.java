package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 룰 {@code gradeRequired.whenLargeGa}이고 테넌트가 대형 GA이면 모든 비교 항목에 엔진 결과가 있다. 결과가 UNAVAILABLE인 항목은
 * "결과 있음"으로 보고 R-GRADE-UNAVAILABLE이 따로 다룬다(설계서 §6.2).
 */
final class GradeRequired implements Validation {

    static final String ID = "R-GRADE-REQUIRED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        if (!(rule.gradeRequiredWhenLargeGa() && subject.largeGa())) {
            return ValidationResult.pass(ID, "등급·순위 표기 의무 대상이 아니다");
        }
        if (subject.gradeSnapshot().isEmpty()) {
            return ValidationResult.fail(ID, "대형 GA는 등급·순위가 필수인데 엔진 스냅샷이 없다");
        }
        GradeSnapshot snapshot = subject.gradeSnapshot().get();
        Set<ProductKey> graded = snapshot.items().stream().map(GradeSnapshotItem::productKey).collect(Collectors.toSet());
        List<ProductKey> missing = subject.items().stream().map(ValidationSubject.Item::productKey)
                .filter(k -> !graded.contains(k)).toList();
        return missing.isEmpty()
                ? ValidationResult.pass(ID, "모든 항목에 엔진 결과가 있다(스냅샷 " + snapshot.snapshotId() + ")")
                : ValidationResult.fail(ID, "엔진 결과가 없는 항목: " + missing);
    }
}
