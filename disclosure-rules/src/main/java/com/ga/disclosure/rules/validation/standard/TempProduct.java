package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.List;
import java.util.Map;

/**
 * 임시등록 상품은 발행번호가 필수이고, 있어도 관리자 확인 + 플래그가 필요하다(오버라이드 경로만). 발행번호가 없으면 오버라이드 불가.
 * 승인 대상은 임시등록 항목들의 {보험사, 상품명, 발행번호} 집합이다(3A 계획 Q3) — 다른 임시등록 상품으로 바꾸면 승인이 효력을 잃는다.
 */
final class TempProduct implements Validation {

    static final String ID = "R-TEMP-PRODUCT";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        List<ValidationSubject.Item> temp = subject.items().stream().filter(ValidationSubject.Item::tempProduct).toList();
        if (temp.isEmpty()) {
            return ValidationResult.pass(ID, "임시등록 상품이 없다");
        }
        List<String> withoutQuote = temp.stream()
                .filter(i -> i.quoteDocNo().map(String::isBlank).orElse(true))
                .map(ValidationSubject.Item::label).toList();
        if (!withoutQuote.isEmpty()) {
            return ValidationResult.fail(ID, "발행번호가 없는 임시등록 상품: " + withoutQuote);
        }
        List<Map<String, String>> entries = temp.stream().map(i -> Map.of("insurer", i.insurerCode().value(),
                "productName", i.productName(), "quoteDocNo", i.quoteDocNo().orElseThrow())).toList();
        return ValidationResult.failOverridable(ID, "임시등록 상품 " + temp.stream().map(ValidationSubject.Item::label).toList()
                + " — 관리자 확인과 준법 플래그가 필요하다", OverrideSubject.setOf(entries));
    }
}
