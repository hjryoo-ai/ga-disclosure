package com.ga.disclosure.workflow.retention;

/**
 * 보류 요청 거부(업무 거부): {@code UNKNOWN_REASON}(룰 {@code legalHoldReasons} 밖), {@code TEXT_REQUIRED}, {@code TEXT_TOO_LONG}, {@code ALREADY_HELD},
 * {@code NOT_FOUND}, {@code ALREADY_RELEASED}, {@code BAD_RELEASE_REASON}. 메시지에 사유 텍스트를 싣지 않는다.
 */
public final class LegalHoldRejectedException extends RuntimeException {

    private final String code;

    public LegalHoldRejectedException(String code) {
        super("legal hold request rejected: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
