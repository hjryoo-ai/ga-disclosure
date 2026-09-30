package com.ga.disclosure.workflow.catalog;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 검증을 마친 카탈로그 수입 파일. {@code kind}에 해당하는 목록만 채워지고 나머지는 비어 있다.
 *
 * @param sha256 파일 원문 바이트의 SHA-256(재수입 판정·출처 기록)
 */
public record CatalogFile(CatalogKind kind, String source, LocalDate asOf, String sha256, List<ProductGroup> groups,
                          List<CatalogProduct> products, List<PanelEntry> insurers) {

    public CatalogFile {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(sha256, "sha256");
        groups = List.copyOf(groups);
        products = List.copyOf(products);
        insurers = List.copyOf(insurers);
    }
}
