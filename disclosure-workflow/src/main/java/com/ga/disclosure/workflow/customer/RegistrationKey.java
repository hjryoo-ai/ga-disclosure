package com.ga.disclosure.workflow.customer;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 고객 등록 멱등 키(V6 {@code customer_ref.registration_key}, 3A 계획 Q7). 개인정보가 아닌 불투명 문자열이며 테넌트 안에서 유일하다.
 * 운영 API(Phase 6)에서는 클라이언트가 보내는 {@code Idempotency-Key}이고, 데모 수입기는 {@code demo:<파일 이름>#<행 ID>}를 만든다 —
 * 이것은 데모의 생성 규칙일 뿐 키의 형식 요구가 아니다.
 */
public record RegistrationKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9._:@#/-]{1,128}");

    public RegistrationKey {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid registration key");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
