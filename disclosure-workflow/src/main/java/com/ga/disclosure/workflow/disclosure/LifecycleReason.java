package com.ga.disclosure.workflow.disclosure;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 무효·정정 사유(3B 수용심사 §2-2, V8): 고정 룰 {@code voidReasons}·{@code supersedeReasons}의 닫힌 코드 + 선택 텍스트(행위자 입력). 목록 대조·
 * 길이 상한은 유스케이스가 고정 룰로 한다. 텍스트는 자유 입력이라 개인정보가 섞일 수 있으므로 감사 detail·{@code toString}에 싣지 않는다
 * (절대 규칙 6 — 길이만).
 */
public record LifecycleReason(String code, String textOrNull) {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");

    public LifecycleReason {
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("reason code must match " + CODE.pattern());
        }
        if (textOrNull != null) {
            textOrNull = textOrNull.strip();
            if (textOrNull.isEmpty()) {
                textOrNull = null;
            }
        }
    }

    public int textLength() {
        return textOrNull == null ? 0 : textOrNull.length();
    }

    @Override
    public String toString() {
        return "LifecycleReason[" + code + ", text " + textLength() + " chars]";
    }
}
