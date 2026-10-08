package com.ga.disclosure.workflow.sign;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 통지 어댑터의 실패(6A 계획 §7.2). 코드는 닫힌 형식({@code ^[A-Z][A-Z0-9_]{0,63}$}, {@code notification_outbox.last_error_code})이고 자유 텍스트를 싣지
 * 않는다 — 사업자 응답 본문·번호·링크는 메시지에 없다. 다른 예외는 디스패처가 {@code ADAPTER_ERROR}로 적는다.
 */
public final class NotifyFailure extends RuntimeException {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final String code;

    public NotifyFailure(String code) {
        super("notification failed: " + check(code));
        this.code = code;
    }

    private static String check(String code) {
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("notify failure codes are closed upper-case identifiers");
        }
        return code;
    }

    public String code() {
        return code;
    }
}
