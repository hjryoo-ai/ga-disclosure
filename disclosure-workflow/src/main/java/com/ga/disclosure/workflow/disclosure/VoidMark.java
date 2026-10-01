package com.ga.disclosure.workflow.disclosure;

import java.time.Instant;
import java.util.Objects;

/**
 * 무효 표시(V1 메타 컬럼 {@code voided_at}·{@code void_reason}). 사유는 행위자 입력이며 감사 detail에는 싣지 않는다(자유 텍스트 — 개인정보가 섞일 수
 * 있다, 절대 규칙 6). {@code toString}은 사유를 드러내지 않는다.
 */
public record VoidMark(Instant at, String reason) {

    public VoidMark {
        Objects.requireNonNull(at, "at");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a void needs a reason");
        }
        if (reason.length() > 2000) {
            throw new IllegalArgumentException("void reason is at most 2000 chars");
        }
    }

    @Override
    public String toString() {
        return "VoidMark[" + at + ", reason " + reason.length() + " chars]";
    }
}
