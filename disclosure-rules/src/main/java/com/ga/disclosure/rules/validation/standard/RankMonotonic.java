package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.rules.grade.GradeConsistencyCheck;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.List;

/**
 * 엔진 스냅샷 정합성(설계서 §6.3 (i)~(v)). 판정은 {@link GradeConsistencyCheck}가 한다. 실패는 스냅샷 거부 — 오버라이드 없음.
 * 요청 집합 = 임시등록을 제외한 항목(임시등록은 엔진에 보내지 않는다, 설계서 §4.1 v1.7).
 */
final class RankMonotonic implements Validation {

    static final String ID = "R-RANK-MONOTONIC";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        if (subject.gradeSnapshot().isEmpty()) {
            return ValidationResult.pass(ID, "검증할 스냅샷이 없다(존재 여부는 R-GRADE-REQUIRED)");
        }
        GradeSnapshot snapshot = subject.gradeSnapshot().get();
        List<String> violations = GradeConsistencyCheck.violations(
                subject.items().stream().map(ValidationSubject.Item::productKey).flatMap(java.util.Optional::stream).toList(), snapshot,
                rule.allowedGradingPolicies(), rule.allowedRankingPolicies(), rule.allowedTieBreaks());
        return violations.isEmpty()
                ? ValidationResult.pass(ID, "스냅샷 " + snapshot.snapshotId() + " 정합(" + snapshot.tieBreak() + ")")
                : ValidationResult.fail(ID, "스냅샷 " + snapshot.snapshotId() + " 거부: " + violations);
    }
}
