package com.ga.disclosure.domain.vo;

import java.util.regex.Pattern;

/** 값객체 생성자 검증 공통. */
final class Patterns {

    /** 대문자·숫자로 시작, 대문자·숫자·밑줄·하이픈. 외부 코드 체계(보험사·상품군·룰 버전 등). */
    static final Pattern UPPER_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,63}");

    /** 영숫자로 시작, 영숫자·밑줄·하이픈·점. 콜론 불가(상품키 구분자). */
    static final Pattern OPAQUE_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private Patterns() {
    }

    static String require(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + what + ": " + value);
        }
        return value;
    }
}
