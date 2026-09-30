package com.ga.disclosure.workflow.catalog;

import java.util.Objects;
import java.util.UUID;

/**
 * 수입 결과. NOOP이면 같은 파일(kind·SHA-256)이 이미 수입된 것이고 {@code importId}는 그 원래 수입이다(감사 행만 남음).
 */
public record CatalogImportOutcome(CatalogKind kind, Result result, UUID importId, String fileSha256, ImportCounts counts) {

    public enum Result {
        IMPORTED,
        NOOP
    }

    public CatalogImportOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(importId, "importId");
        Objects.requireNonNull(fileSha256, "fileSha256");
        Objects.requireNonNull(counts, "counts");
    }
}
