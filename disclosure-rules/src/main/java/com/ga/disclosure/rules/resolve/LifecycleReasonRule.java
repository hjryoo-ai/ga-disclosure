package com.ga.disclosure.rules.resolve;

import java.util.Objects;

/** 무효·정정 사유 코드 1건(룰 {@code voidReasons}·{@code supersedeReasons}, 3B 수용심사 §2-2). {@code requiresText}면 텍스트 필수. */
public record LifecycleReasonRule(String code, String label, boolean requiresText) {

    public LifecycleReasonRule {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
    }
}
