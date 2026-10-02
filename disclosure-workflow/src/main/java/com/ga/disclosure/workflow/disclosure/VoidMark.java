package com.ga.disclosure.workflow.disclosure;

import java.time.Instant;
import java.util.Objects;

/**
 * 무효 표시(메타 컬럼 {@code voided_at}·{@code void_reason_code}·{@code void_reason_text}, V8). 사유 텍스트는 감사 detail에 싣지 않는다 —
 * {@link LifecycleReason}.
 */
public record VoidMark(Instant at, LifecycleReason reason) {

    public VoidMark {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(reason, "reason");
    }
}
