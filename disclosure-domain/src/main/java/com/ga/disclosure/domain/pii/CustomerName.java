package com.ga.disclosure.domain.pii;

import com.ga.disclosure.domain.enums.PiiField;

import java.text.Normalizer;

/** 고객 이름. NFC 정규화·앞뒤 공백 제거, 1~100자, 제어 문자 금지. 오류 메시지에 입력값을 넣지 않는다. */
public final class CustomerName implements SensitiveValue {

    private static final int MAX_CODE_POINTS = 100;

    private final String value;

    private CustomerName(String value) {
        this.value = value;
    }

    public static Sensitive<CustomerName> of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("customer name is required");
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length == 0 || length > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("customer name must be 1.." + MAX_CODE_POINTS + " characters");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("customer name must not contain control characters");
        }
        return Sensitive.of(new CustomerName(normalized));
    }

    @Override
    public PiiField field() {
        return PiiField.NAME;
    }

    @Override
    public String canonical() {
        return value;
    }

    @Override
    public String toString() {
        return "CustomerName[****]";
    }
}
