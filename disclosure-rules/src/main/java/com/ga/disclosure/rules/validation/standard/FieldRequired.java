package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateField;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 서식의 {@code required=true} 항목 값이 있다. 항목 목록은 서식 데이터({@link TemplateResolution#requiredFields()})이고 값은
 * {@link ValidationSubject}의 항목별·확인서별 값만 본다. {@code pendingConfirmation}은 필드가 아니므로 관여하지 않는다.
 */
final class FieldRequired implements Validation {

    static final String ID = "R-FIELD-REQUIRED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        List<String> missing = new ArrayList<>();
        for (TemplateField field : template.requiredFields()) {
            switch (field.scope()) {
                case PER_DOCUMENT -> {
                    if (blank(subject.documentFieldValues(), field.code())) {
                        missing.add(field.code());
                    }
                }
                case PER_ITEM -> subject.items().stream()
                        .filter(i -> blank(i.fieldValues(), field.code()))
                        .forEach(i -> missing.add(field.code() + "@" + i.productKey()));
            }
        }
        return missing.isEmpty()
                ? ValidationResult.pass(ID, "서식 " + template.ref().templateId() + " v" + template.ref().version() + " 필수 항목 "
                        + template.requiredFieldCodes().size() + "개 충족")
                : ValidationResult.fail(ID, "값이 없는 필수 항목: " + missing);
    }

    private static boolean blank(Map<String, String> values, String code) {
        String v = values.get(code);
        return v == null || v.isBlank();
    }
}
