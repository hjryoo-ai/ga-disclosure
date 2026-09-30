package com.ga.disclosure.domain.vo;

import java.util.regex.Pattern;

/** 값객체 생성자 검증 공통. */
final class Patterns {

    /** 대문자·숫자로 시작, 대문자·숫자·밑줄·하이픈. 외부 코드 체계(보험사·상품군·룰 버전 등). */
    static final Pattern UPPER_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,63}");

    /** 영숫자로 시작, 영숫자·밑줄·하이픈·점. 콜론 불가(상품키 구분자). */
    static final Pattern OPAQUE_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    /** 보험사 코드: 대문자·숫자로 시작, 대문자·숫자·하이픈, 8자 이하(엔진 계약 1.2.0 {@code InsurerCode}). */
    static final Pattern INSURER_CODE = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,7}");

    /** 상품키의 상품 코드 부분: 영숫자로 시작, 영숫자·점·밑줄·하이픈, 31자 이하(키 전체 40자 이하, 엔진 계약 1.2.0 {@code ProductKey}). */
    static final Pattern PRODUCT_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,30}");

    private Patterns() {
    }

    static String require(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + what + ": " + value);
        }
        return value;
    }
}
