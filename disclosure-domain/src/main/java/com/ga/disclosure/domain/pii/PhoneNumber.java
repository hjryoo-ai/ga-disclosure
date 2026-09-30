package com.ga.disclosure.domain.pii;

import com.ga.disclosure.domain.enums.PiiField;

import java.util.regex.Pattern;

/**
 * 고객 휴대전화 번호(원격 서명 링크 발송 전용, 설계서 §6.5). 하이픈·공백을 뺀 숫자만 저장한다. 형식은 국내 휴대전화
 * {@code 01[016789]} + 7~8자리. 오류 메시지에 입력값을 넣지 않는다.
 */
public final class PhoneNumber implements SensitiveValue {

    private static final Pattern MOBILE = Pattern.compile("01[016789][0-9]{7,8}");

    private final String digits;

    private PhoneNumber(String digits) {
        this.digits = digits;
    }

    public static Sensitive<PhoneNumber> of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("phone number is required");
        }
        String digits = raw.replace("-", "").replace(" ", "");
        if (!MOBILE.matcher(digits).matches()) {
            throw new IllegalArgumentException("phone number must be a domestic mobile number (01X + 7..8 digits)");
        }
        return Sensitive.of(new PhoneNumber(digits));
    }

    @Override
    public PiiField field() {
        return PiiField.PHONE;
    }

    @Override
    public String canonical() {
        return digits;
    }

    @Override
    public String toString() {
        return "PhoneNumber[****]";
    }
}
