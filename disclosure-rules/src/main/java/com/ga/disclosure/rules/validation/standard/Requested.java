package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 고객 요청 항목에는 룰 {@code reasonCodes[].auto=true}인 코드가 전부 붙어 있고, 요청 항목이 아니면 하나도 붙어 있지 않다.
 * 어떤 코드가 자동 부가 대상인지는 룰 데이터가 정한다(코드에 사유 코드 이름을 두지 않는다). 최소 비교 개수 산입은 R-MIN-COMPARE.
 */
final class Requested implements Validation {

    static final String ID = "R-REQUESTED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        Set<ReasonCode> auto = rule.autoReasonCodes();
        List<String> problems = new ArrayList<>();
        for (ValidationSubject.Item item : subject.items()) {
            Set<ReasonCode> codes = new HashSet<>(item.reasonCodes());
            if (item.requestedByCustomer() && !codes.containsAll(auto)) {
                Set<ReasonCode> missing = new HashSet<>(auto);
                missing.removeAll(codes);
                problems.add(item.productKey() + ": 고객 요청 항목에 자동 부가 코드 " + missing + "가 없다");
            }
            if (!item.requestedByCustomer()) {
                codes.retainAll(auto);
                if (!codes.isEmpty()) {
                    problems.add(item.productKey() + ": 고객 요청이 아닌 항목에 자동 부가 코드 " + codes + "가 있다");
                }
            }
        }
        return problems.isEmpty()
                ? ValidationResult.pass(ID, "고객 요청 표시와 자동 부가 코드가 일치한다")
                : ValidationResult.fail(ID, String.join("; ", problems));
    }
}
