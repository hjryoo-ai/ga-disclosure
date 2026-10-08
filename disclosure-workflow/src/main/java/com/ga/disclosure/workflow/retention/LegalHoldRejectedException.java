package com.ga.disclosure.workflow.retention;

import com.ga.disclosure.workflow.RejectionCategory;

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

    /** 이미 보류됨·이미 해제됨은 상태 충돌, 나머지(사유·텍스트·4-eyes)는 요청 거부. */
    public RejectionCategory category() {
        return code.equals("ALREADY_HELD") || code.equals("ALREADY_RELEASED") ? RejectionCategory.CONFLICT : RejectionCategory.INVALID;
    }
}
