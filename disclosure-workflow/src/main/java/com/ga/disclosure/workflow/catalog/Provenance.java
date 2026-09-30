package com.ga.disclosure.workflow.catalog;

import java.time.Instant;
import java.util.Objects;

/**
 * 카탈로그 행의 출처(설계서 §5): 공급처({@code source}), 수입 파일({@code sourceRef = '{파일명}@sha256:{hex}'}), 동기 시각.
 * 파일에 있었거나 파일 때문에 닫힌 행은 그 수입의 출처로 갱신된다 — "이 행을 마지막으로 확인한 파일".
 */
public record Provenance(String source, String sourceRef, Instant syncedAt) {

    public Provenance {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceRef, "sourceRef");
        Objects.requireNonNull(syncedAt, "syncedAt");
    }
}
