package com.ga.disclosure.workflow.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** {@code catalog_import} 1행(append-only). 같은 (kind, fileSha256)은 한 번만 수입된다. */
public record ImportRecord(UUID importId, CatalogKind kind, String fileName, String fileSha256, String source, LocalDate asOf,
                           Instant importedAt, ImportCounts counts) {

    public ImportRecord {
        Objects.requireNonNull(importId, "importId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(fileSha256, "fileSha256");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(importedAt, "importedAt");
        Objects.requireNonNull(counts, "counts");
    }
}
