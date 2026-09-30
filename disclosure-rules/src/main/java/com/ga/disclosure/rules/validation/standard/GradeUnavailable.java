package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.domain.disclosure.GradeSource;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UNAVAILABLE(산출불가) 항목이 있으면 "산출불가·사유" 표기 + 관리자 확인 + 플래그가 필요하다 — 오버라이드 경로로만 넘어간다.
 * 사유가 없으면 표기 자체가 불가능하므로 오버라이드도 불가.
 *
 * <p>산출불가 = 엔진이 UNAVAILABLE로 답한 항목(ENGINE) + 스냅샷이 적용된 확인서의 임시등록 항목(LOCAL {@code TEMP_PRODUCT}, 엔진에
 * 보내지 않는다 — Phase 2 심사 §3-5). 승인 대상은 {항목, 사유, 출처}의 집합이다(3A 계획 Q3): 재산출해도 같은 집합이면 승인이 유지된다.
 */
final class GradeUnavailable implements Validation {

    static final String ID = "R-GRADE-UNAVAILABLE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        if (subject.gradeSnapshot().isEmpty()) {
            return ValidationResult.pass(ID, "산출 전이다(스냅샷 존재는 R-GRADE-REQUIRED)");
        }
        List<Map<String, String>> unavailable = new ArrayList<>();
        boolean missingReason = false;
        for (GradeSnapshotItem i : subject.gradeSnapshot().get().items()) {
            if (!i.isAvailable()) {
                missingReason |= i.unavailableReason() == null || i.unavailableReason().isBlank();
                unavailable.add(Map.of("item", i.productKey().value(), "reason", String.valueOf(i.unavailableReason()),
                        "source", GradeSource.ENGINE.name()));
            }
        }
        subject.items().stream().filter(ValidationSubject.Item::tempProduct).forEach(i -> unavailable.add(Map.of(
                "item", i.label(), "reason", ItemGrade.TEMP_PRODUCT_REASON, "source", GradeSource.LOCAL.name())));
        if (unavailable.isEmpty()) {
            return ValidationResult.pass(ID, "산출불가 항목이 없다");
        }
        if (missingReason) {
            return ValidationResult.fail(ID, "산출불가 사유가 없는 항목이 있어 표기할 수 없다");
        }
        return ValidationResult.failOverridable(ID, "산출불가 항목 " + unavailable.stream()
                .map(u -> u.get("item") + "(" + u.get("reason") + ", " + u.get("source") + ")").toList()
                + " — 관리자 확인과 준법 플래그가 필요하다", OverrideSubject.setOf(unavailable));
    }
}
