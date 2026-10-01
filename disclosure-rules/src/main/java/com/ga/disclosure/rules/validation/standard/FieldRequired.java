package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.BindingResolver;
import com.ga.disclosure.rules.template.BindingView;
import com.ga.disclosure.rules.template.TemplateField;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.ArrayList;
import java.util.List;

/**
 * 서식의 {@code required=true} 항목 값이 있다. 항목 목록은 서식 데이터({@link TemplateResolution#requiredFields()})이고, 값은 항목의
 * 결속({@code render.bind})이 가리키는 값이다 — 렌더러와 같은 {@link BindingResolver}로 찾는다(3A 수용심사 §3-1, 출처별 판정 폐기).
 * 확인서 번호·고객 성명은 봉인이 채우므로 검증 시점에는 "발급 예정"으로 존재한다(성명 복호화 가능 여부는 봉인 조건이 따로 본다).
 * {@code pendingConfirmation}은 필드가 아니므로 관여하지 않는다.
 */
final class FieldRequired implements Validation {

    static final String ID = "R-FIELD-REQUIRED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        BindingView bindings = subject.bindings();
        List<ValidationSubject.Item> items = subject.items();
        if (bindings.items().size() != items.size()) {
            throw new IllegalStateException("binding view has " + bindings.items().size() + " items, subject has " + items.size());
        }
        List<String> missing = new ArrayList<>();
        for (TemplateField field : template.requiredFields()) {
            switch (field.scope()) {
                case PER_DOCUMENT -> {
                    if (!BindingResolver.present(field, bindings)) {
                        missing.add(field.code());
                    }
                }
                case PER_ITEM -> {
                    for (int i = 0; i < items.size(); i++) {
                        if (!BindingResolver.present(field, bindings.items().get(i))) {
                            missing.add(field.code() + "@" + items.get(i).label());
                        }
                    }
                }
            }
        }
        return missing.isEmpty()
                ? ValidationResult.pass(ID, "서식 " + template.ref().templateId() + " v" + template.ref().version() + " 필수 항목 "
                        + template.requiredFieldCodes().size() + "개 충족")
                : ValidationResult.fail(ID, "값이 없는 필수 항목: " + missing);
    }
}
