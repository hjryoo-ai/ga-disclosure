package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.enums.InsuranceLine;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.workflow.catalog.CatalogKind;
import com.ga.disclosure.workflow.catalog.CatalogProduct;
import com.ga.disclosure.workflow.catalog.CatalogStore;
import com.ga.disclosure.workflow.catalog.ImportCounts;
import com.ga.disclosure.workflow.catalog.ImportRecord;
import com.ga.disclosure.workflow.catalog.InsurerPanelPort;
import com.ga.disclosure.workflow.catalog.PanelEntry;
import com.ga.disclosure.workflow.catalog.ProductCatalogPort;
import com.ga.disclosure.workflow.catalog.ProductGroup;
import com.ga.disclosure.workflow.catalog.Provenance;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 카탈로그 어댑터(설계서 §4.2): 파일 수입 캐시의 쓰기({@link CatalogStore})와 기준일 조회({@link ProductCatalogPort},
 * {@link InsurerPanelPort}). 유효기간은 반개구간 {@code from <= asOf AND (to IS NULL OR to > asOf)}. 행을 지우는 SQL은 없다.
 */
@Repository
public class CatalogRepository extends TenantScopedRepository implements CatalogStore, ProductCatalogPort, InsurerPanelPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final RowMapper<ProductGroup> GROUP = (rs, n) -> new ProductGroup(GroupCode.of(rs.getString("group_code")),
            rs.getString("name"), InsuranceLine.valueOf(rs.getString("line")), rs.getObject("apply_from", LocalDate.class),
            rs.getObject("apply_to", LocalDate.class));

    private static final RowMapper<CatalogProduct> PRODUCT = (rs, n) -> new CatalogProduct(ProductKey.parse(rs.getString("product_key")),
            GroupCode.of(rs.getString("group_code")), rs.getString("product_name"), rs.getObject("sale_from", LocalDate.class),
            rs.getObject("sale_to", LocalDate.class), JSON.readTree(rs.getString("defaults")));

    private static final RowMapper<PanelEntry> PANEL = (rs, n) -> new PanelEntry(InsurerCode.of(rs.getString("insurer_code")),
            rs.getString("insurer_name"), InsuranceLine.valueOf(rs.getString("line")), rs.getObject("active_from", LocalDate.class),
            rs.getObject("active_to", LocalDate.class));

    private static final RowMapper<ImportRecord> IMPORT = (rs, n) -> new ImportRecord(rs.getObject("import_id", UUID.class),
            CatalogKind.valueOf(rs.getString("kind")), rs.getString("file_name"), rs.getString("file_sha256"), rs.getString("source"),
            rs.getObject("as_of", LocalDate.class), rs.getTimestamp("imported_at").toInstant(),
            new ImportCounts(rs.getInt("inserted"), rs.getInt("updated"), rs.getInt("closed"), rs.getInt("unchanged")));

    private static final String GROUP_COLUMNS = "group_code, name, line, apply_from, apply_to";
    private static final String PRODUCT_COLUMNS = "product_key, group_code, product_name, sale_from, sale_to, defaults::text AS defaults";
    private static final String PANEL_COLUMNS = "insurer_code, insurer_name, line, active_from, active_to";
    private static final String IMPORT_COLUMNS =
            "import_id, kind, file_name, file_sha256, source, as_of, imported_at, inserted, updated, closed, unchanged";

    public CatalogRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    // ------------------------------------------------------------------ 조회 포트(기준일 필수)

    @Override
    public List<ProductGroup> listGroups(TenantId tenant, LocalDate asOf) {
        requireBound(tenant, asOf);
        return query("SELECT " + GROUP_COLUMNS + """
                  FROM product_group
                 WHERE tenant_id = :tenantId
                   AND apply_from <= :asOf
                   AND (apply_to IS NULL OR apply_to > :asOf)
                 ORDER BY group_code
                """, Map.of("asOf", asOf), GROUP);
    }

    @Override
    public List<CatalogProduct> searchProducts(TenantId tenant, GroupCode group, Optional<InsurerCode> insurer, String keyword,
                                               LocalDate asOf) {
        requireBound(tenant, asOf);
        Objects.requireNonNull(group, "group");
        Map<String, Object> params = new HashMap<>();
        params.put("asOf", asOf);
        params.put("groupCode", group.value());
        params.put("insurerCode", insurer.map(InsurerCode::value).orElse(null));
        params.put("keyword", keyword == null || keyword.isBlank() ? null : "%" + likeEscape(keyword.strip()) + "%");
        return query("SELECT " + PRODUCT_COLUMNS + """
                  FROM product_catalog
                 WHERE tenant_id = :tenantId
                   AND group_code = :groupCode
                   AND (CAST(:insurerCode AS text) IS NULL OR insurer_code = :insurerCode)
                   AND (CAST(:keyword AS text) IS NULL OR product_name ILIKE :keyword ESCAPE '\\')
                   AND sale_from <= :asOf
                   AND (sale_to IS NULL OR sale_to > :asOf)
                 ORDER BY product_key
                """, params, PRODUCT);
    }

    @Override
    public Optional<CatalogProduct> getProduct(TenantId tenant, ProductKey key, LocalDate asOf) {
        requireBound(tenant, asOf);
        return queryAtMostOne("SELECT " + PRODUCT_COLUMNS + """
                  FROM product_catalog
                 WHERE tenant_id = :tenantId
                   AND product_key = :productKey
                   AND sale_from <= :asOf
                   AND (sale_to IS NULL OR sale_to > :asOf)
                """, Map.of("productKey", key.value(), "asOf", asOf), PRODUCT);
    }

    @Override
    public boolean isOnPanel(TenantId tenant, InsurerCode insurer, LocalDate date) {
        requireBound(tenant, date);
        return !query("""
                SELECT insurer_code
                  FROM insurer_panel
                 WHERE tenant_id = :tenantId
                   AND insurer_code = :insurerCode
                   AND active_from <= :asOf
                   AND (active_to IS NULL OR active_to > :asOf)
                """, Map.of("insurerCode", insurer.value(), "asOf", date), (rs, n) -> rs.getString(1)).isEmpty();
    }

    @Override
    public List<PanelEntry> panel(TenantId tenant, LocalDate asOf) {
        requireBound(tenant, asOf);
        return query("SELECT " + PANEL_COLUMNS + """
                  FROM insurer_panel
                 WHERE tenant_id = :tenantId
                   AND active_from <= :asOf
                   AND (active_to IS NULL OR active_to > :asOf)
                 ORDER BY insurer_code
                """, Map.of("asOf", asOf), PANEL);
    }

    // ------------------------------------------------------------------ 수입(쓰기) 포트

    @Override
    public Optional<ImportRecord> findImport(CatalogKind kind, String fileSha256) {
        return queryAtMostOne("SELECT " + IMPORT_COLUMNS + """
                  FROM catalog_import
                 WHERE tenant_id = :tenantId
                   AND kind = :kind
                   AND file_sha256 = :sha
                """, Map.of("kind", kind.name(), "sha", fileSha256), IMPORT);
    }

    @Override
    public Optional<ImportRecord> latestImport(CatalogKind kind) {
        return query("SELECT " + IMPORT_COLUMNS + """
                  FROM catalog_import
                 WHERE tenant_id = :tenantId
                   AND kind = :kind
                 ORDER BY as_of DESC, imported_at DESC
                 LIMIT 1
                """, Map.of("kind", kind.name()), IMPORT).stream().findFirst();
    }

    @Override
    public void recordImport(ImportRecord r) {
        Map<String, Object> params = new HashMap<>();
        params.put("importId", r.importId());
        params.put("kind", r.kind().name());
        params.put("fileName", r.fileName());
        params.put("sha", r.fileSha256());
        params.put("source", r.source());
        params.put("asOf", r.asOf());
        params.put("importedAt", Timestamp.from(r.importedAt()));
        params.put("inserted", r.counts().inserted());
        params.put("updated", r.counts().updated());
        params.put("closed", r.counts().closed());
        params.put("unchanged", r.counts().unchanged());
        update("""
                INSERT INTO catalog_import (tenant_id, import_id, kind, file_name, file_sha256, source, as_of, imported_at,
                                            inserted, updated, closed, unchanged)
                VALUES (:tenantId, :importId, :kind, :fileName, :sha, :source, :asOf, :importedAt, :inserted, :updated, :closed, :unchanged)
                """, params);
    }

    @Override
    public List<ProductGroup> groups() {
        return query("SELECT " + GROUP_COLUMNS + " FROM product_group WHERE tenant_id = :tenantId ORDER BY group_code", Map.of(), GROUP);
    }

    @Override
    public List<CatalogProduct> products() {
        return query("SELECT " + PRODUCT_COLUMNS + " FROM product_catalog WHERE tenant_id = :tenantId ORDER BY product_key", Map.of(),
                PRODUCT);
    }

    @Override
    public List<PanelEntry> panel() {
        return query("SELECT " + PANEL_COLUMNS + " FROM insurer_panel WHERE tenant_id = :tenantId ORDER BY insurer_code, active_from",
                Map.of(), PANEL);
    }

    @Override
    public void saveGroup(ProductGroup g, Provenance p, boolean isNew) {
        Map<String, Object> params = provenance(p);
        params.put("groupCode", g.code().value());
        params.put("name", g.name());
        params.put("line", g.line().name());
        params.put("applyFrom", g.applyFrom());
        params.put("applyTo", g.applyTo());
        exactlyOne(update(isNew ? """
                INSERT INTO product_group (tenant_id, group_code, name, line, apply_from, apply_to, source, source_ref, synced_at)
                VALUES (:tenantId, :groupCode, :name, :line, :applyFrom, :applyTo, :source, :sourceRef, :syncedAt)
                """ : """
                UPDATE product_group
                   SET name = :name, line = :line, apply_from = :applyFrom, apply_to = :applyTo,
                       source = :source, source_ref = :sourceRef, synced_at = :syncedAt
                 WHERE tenant_id = :tenantId
                   AND group_code = :groupCode
                """, params));
    }

    @Override
    public void saveProduct(CatalogProduct c, Provenance p, boolean isNew) {
        Map<String, Object> params = provenance(p);
        params.put("productKey", c.key().value());
        params.put("insurerCode", c.insurer().value());
        params.put("groupCode", c.group().value());
        params.put("name", c.name());
        params.put("saleFrom", c.saleFrom());
        params.put("saleTo", c.saleTo());
        params.put("defaults", JSON.writeValueAsString(c.defaults()));
        exactlyOne(update(isNew ? """
                INSERT INTO product_catalog (tenant_id, product_key, insurer_code, group_code, product_name, sale_from, sale_to, defaults,
                                             source, source_ref, synced_at)
                VALUES (:tenantId, :productKey, :insurerCode, :groupCode, :name, :saleFrom, :saleTo, CAST(:defaults AS jsonb),
                        :source, :sourceRef, :syncedAt)
                """ : """
                UPDATE product_catalog
                   SET group_code = :groupCode, product_name = :name, sale_from = :saleFrom, sale_to = :saleTo,
                       defaults = CAST(:defaults AS jsonb), source = :source, source_ref = :sourceRef, synced_at = :syncedAt
                 WHERE tenant_id = :tenantId
                   AND product_key = :productKey
                   AND insurer_code = :insurerCode
                """, params));
    }

    @Override
    public void savePanelEntry(PanelEntry e, Provenance p, boolean isNew) {
        Map<String, Object> params = provenance(p);
        params.put("insurerCode", e.insurer().value());
        params.put("insurerName", e.insurerName());
        params.put("line", e.line().name());
        params.put("activeFrom", e.activeFrom());
        params.put("activeTo", e.activeTo());
        exactlyOne(update(isNew ? """
                INSERT INTO insurer_panel (tenant_id, insurer_code, insurer_name, line, active_from, active_to, source, source_ref, synced_at)
                VALUES (:tenantId, :insurerCode, :insurerName, :line, :activeFrom, :activeTo, :source, :sourceRef, :syncedAt)
                """ : """
                UPDATE insurer_panel
                   SET insurer_name = :insurerName, line = :line, active_to = :activeTo,
                       source = :source, source_ref = :sourceRef, synced_at = :syncedAt
                 WHERE tenant_id = :tenantId
                   AND insurer_code = :insurerCode
                   AND active_from = :activeFrom
                """, params));
    }

    // ------------------------------------------------------------------

    private static void exactlyOne(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("catalog write touched " + rows + " rows (expected 1)");
        }
    }

    private static Map<String, Object> provenance(Provenance p) {
        Map<String, Object> params = new HashMap<>();
        params.put("source", p.source());
        params.put("sourceRef", p.sourceRef());
        params.put("syncedAt", Timestamp.from(p.syncedAt()));
        return params;
    }

    private static String likeEscape(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void requireBound(TenantId tenant, LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf is required (no default date)");
        if (!TenantContext.current().equals(tenant)) {
            throw new IllegalArgumentException("port called for " + tenant + " while bound to " + TenantContext.current());
        }
    }
}
