package com.ga.platform.canonical;

import java.io.Serial;

/** 입력이 RFC 8785로 정규화될 수 없다(유효하지 않은 JSON, 중복 키, 짝 없는 서로게이트, 표현 불가 숫자). */
public class CanonicalizationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CanonicalizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
