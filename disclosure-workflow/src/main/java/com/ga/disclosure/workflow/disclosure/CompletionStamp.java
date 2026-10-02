package com.ga.disclosure.workflow.disclosure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 완료 결과(4 계획 §7.3): 완료 시각과 다시 계산한 보존기한. 완료 유스케이스가 서명본·증거 패키지를 만든 뒤 애그리게이트에 건넨다. 보존기한은
 * 연장만이다({@link SealStamp#withRetentionUntil}, DB GD094).
 */
public record CompletionStamp(Instant completedAt, LocalDate retentionUntil) {

    public CompletionStamp {
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(retentionUntil, "retentionUntil");
    }
}
