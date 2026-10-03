package com.ga.disclosure.audit.verify;

import java.util.Objects;

/**
 * 입력 오류(종료 3): 보고서 대신 {@code {code, message}}만 내보낸다(5 계획 §4). 코드: {@code ZIP_CORRUPT}·{@code NOT_AN_EVIDENCE_PACKAGE}·
 * {@code RECEIPT_INVALID}·{@code TRUST_INVALID}·{@code UNKNOWN_TENANT}·{@code FILE_UNREADABLE}. 메시지에 입력 내용을 싣지 않는다.
 */
public final class VerifyInputException extends RuntimeException {

    private final String code;

    public VerifyInputException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public VerifyInputException(String code, String message) {
        this(code, message, null);
    }

    public String code() {
        return code;
    }
}
