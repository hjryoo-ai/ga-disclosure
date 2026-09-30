package com.ga.disclosure.workflow.catalog;

import java.util.List;
import java.util.Optional;

/**
 * 카탈로그 쓰기 포트(infra 어댑터가 구현). 바인딩된 테넌트의 트랜잭션 안에서 호출된다. 행을 지우는 연산은 없다 —
 * 파일에서 사라진 행은 유효기간을 닫아 저장한다(DB도 DELETE를 거부, GD070).
 */
public interface CatalogStore {

    Optional<ImportRecord> findImport(CatalogKind kind, String fileSha256);

    /** 같은 kind의 가장 늦은 기준일 수입(asOf 단조성 판정). */
    Optional<ImportRecord> latestImport(CatalogKind kind);

    void recordImport(ImportRecord record);

    List<ProductGroup> groups();

    List<CatalogProduct> products();

    List<PanelEntry> panel();

    /** {@code isNew}면 INSERT, 아니면 같은 키 행의 내용·출처를 UPDATE. */
    void saveGroup(ProductGroup group, Provenance provenance, boolean isNew);

    void saveProduct(CatalogProduct product, Provenance provenance, boolean isNew);

    /** 패널의 키는 (보험사, activeFrom)이다. */
    void savePanelEntry(PanelEntry entry, Provenance provenance, boolean isNew);
}
