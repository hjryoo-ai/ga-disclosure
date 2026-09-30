package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.catalog.CatalogImportOutcome;
import com.ga.disclosure.workflow.catalog.CatalogImportRejectedException;
import com.ga.disclosure.workflow.catalog.InvalidCatalogFileException;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.ga.disclosure.infra.CatalogFiles.group;
import static com.ga.disclosure.infra.CatalogFiles.groups;
import static com.ga.disclosure.infra.CatalogFiles.product;
import static com.ga.disclosure.infra.CatalogFiles.products;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 P2: 카탈로그 수입은 행 단위 upsert + 유효기간 닫기다. 같은 파일 재수입은 no-op(파일 해시, 감사 NOOP), 파일에서 사라진
 * 상품은 삭제가 아니라 {@code sale_to}가 수입 기준일로 닫히며, 행마다 출처·파일 해시·동기 시각이 남는다. DB도 삭제를 거부한다.
 */
class CatalogImportIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String GROUPS = groups("2026-09-01", group("PG-HEALTH", "2026-01-01", null));
    private static final String V1 = products("2026-09-01",
            product("INS-A:PRD-1", "PG-HEALTH", "(가상) 상품 A", "2026-01-01", null),
            product("INS-B:PRD-2", "PG-HEALTH", "(가상) 상품 B", "2026-01-01", null),
            product("INS-C:PRD-3", "PG-HEALTH", "(가상) 출시 예정 C", "2026-12-01", null));
    /** 9/15 파일: A 이름 변경, B 사라짐, C(개시 전) 사라짐, D 신규. */
    private static final String V2 = products("2026-09-15",
            product("INS-A:PRD-1", "PG-HEALTH", "(가상) 상품 A 개정", "2026-01-01", null),
            product("INS-D:PRD-4", "PG-HEALTH", "(가상) 상품 D", "2026-09-15", null));

    private final CatalogCustomerSetup s = new CatalogCustomerSetup("2026-09-01T01:00:00Z");

    private TenantId withGroups() {
        TenantId t = s.freshTenant("IMP");
        s.importJson(t, "groups.json", GROUPS);
        return t;
    }

    private List<String> column(TenantId t, String sql) {
        return DB.asApp(t.value(), c -> {
            List<String> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    @Test
    void sameFileAgainIsANoopRecordedInTheAuditLog() {
        TenantId t = withGroups();
        CatalogImportOutcome first = s.importJson(t, "products-2026-09-01.json", V1);
        assertThat(first.result()).isEqualTo(CatalogImportOutcome.Result.IMPORTED);
        assertThat(first.counts().inserted()).isEqualTo(3);
        CatalogImportOutcome again = s.at("2026-09-02T01:00:00Z").importJson(t, "renamed-copy.json", V1);
        assertThat(again.result()).isEqualTo(CatalogImportOutcome.Result.NOOP);
        assertThat(again.importId()).isEqualTo(first.importId());
        assertThat(again.fileSha256()).isEqualTo(Sha256.of(V1.getBytes(StandardCharsets.UTF_8)));

        assertThat(column(t, "SELECT count(*) FROM catalog_import WHERE kind = 'PRODUCTS'")).containsExactly("1");
        assertThat(column(t, "SELECT DISTINCT synced_at::text FROM product_catalog")).as("no row was touched by the no-op").hasSize(1);
        List<AuditRecord> imports = s.auditOf(t).stream().filter(r -> r.entry().action() == AuditAction.CATALOG_IMPORT).toList();
        assertThat(imports).extracting(r -> r.entry().detail().path("outcome").asString()).containsExactly("IMPORTED", "IMPORTED", "NOOP");
        assertThat(imports.getLast().entry().detail().path("originalImportId").asString()).isEqualTo(first.importId().toString());
    }

    @Test
    void missingProductsAreClosedAtTheImportDateNotDeleted() {
        TenantId t = withGroups();
        s.importJson(t, "v1.json", V1);
        CatalogImportOutcome second = s.at("2026-09-15T02:00:00Z").importJson(t, "v2.json", V2);
        assertThat(second.counts()).satisfies(c -> {
            assertThat(c.inserted()).isEqualTo(1);
            assertThat(c.updated()).isEqualTo(1);
            assertThat(c.closed()).isEqualTo(2);
            assertThat(c.unchanged()).isZero();
        });
        assertThat(column(t, "SELECT product_key || '|' || coalesce(sale_to::text, '-') FROM product_catalog ORDER BY product_key"))
                .containsExactly("INS-A:PRD-1|-", "INS-B:PRD-2|2026-09-15", "INS-C:PRD-3|2026-12-01", "INS-D:PRD-4|-");
        // 닫힌 B는 과거 기준일에는 여전히 보인다(확인서가 참조한 상품이 사라지지 않는다)
        assertThat(s.in(t, () -> s.catalog.getProduct(t, ProductKey.parse("INS-B:PRD-2"), LocalDate.parse("2026-09-14")))).isPresent();
        assertThat(s.in(t, () -> s.catalog.getProduct(t, ProductKey.parse("INS-B:PRD-2"), LocalDate.parse("2026-09-15")))).isEmpty();
        // 개시 전에 철회된 C는 빈 구간 [2026-12-01, 2026-12-01)
        assertThat(s.in(t, () -> s.catalog.getProduct(t, ProductKey.parse("INS-C:PRD-3"), LocalDate.parse("2026-12-01")))).isEmpty();

        AuditRecord audit = s.auditOf(t).getLast();
        assertThat(audit.entry().action()).isEqualTo(AuditAction.CATALOG_IMPORT);
        List<String> changes = new ArrayList<>();
        audit.entry().detail().path("changes").forEach(ch -> changes.add(ch.path("key").asString() + ":" + ch.path("change").asString()
                + (ch.has("end") ? "@" + ch.path("end").asString() : "")));
        assertThat(changes).containsExactlyInAnyOrder("INS-A:PRD-1:UPDATED", "INS-D:PRD-4:INSERTED", "INS-B:PRD-2:CLOSED@2026-09-15",
                "INS-C:PRD-3:CLOSED@2026-12-01");
        audit.entry().detail().path("changes").forEach(ch -> {
            if (ch.path("change").asString().equals("UPDATED")) {
                assertThat(ch.at("/before/name").asString()).isEqualTo("(가상) 상품 A");
                assertThat(ch.at("/after/name").asString()).isEqualTo("(가상) 상품 A 개정");
            }
        });
    }

    @Test
    void everyRowCarriesSourceFileHashAndSyncTime() {
        TenantId t = withGroups();
        s.importJson(t, "v1.json", V1);
        s.at("2026-09-15T02:00:00Z").importJson(t, "/tmp/some/dir/v2.json", V2);
        String v2 = "v2.json@sha256:" + Sha256.of(V2.getBytes(StandardCharsets.UTF_8));
        assertThat(column(t, "SELECT DISTINCT source || '|' || source_ref || '|' || (synced_at AT TIME ZONE 'Asia/Seoul')::text FROM product_catalog"))
                .as("rows in the file and rows closed by it point to the file (name only, no path)")
                .containsExactly("TEST_FILE|" + v2 + "|2026-09-15 11:00:00");
        assertThat(column(t, "SELECT file_name || '|' || as_of::text || '|' || inserted || '/' || updated || '/' || closed || '/' || unchanged "
                + "FROM catalog_import WHERE kind = 'PRODUCTS' ORDER BY as_of"))
                .containsExactly("v1.json|2026-09-01|3/0/0/0", "v2.json|2026-09-15|1/1/2/0");
    }

    @Test
    void anOlderFileCannotRollTheCacheBack() {
        TenantId t = withGroups();
        s.importJson(t, "v2.json", V2);
        assertThatThrownBy(() -> s.importJson(t, "v1.json", V1)).isInstanceOf(CatalogImportRejectedException.class)
                .hasMessageContaining("earlier than the last import");
        assertThat(column(t, "SELECT count(*) FROM product_catalog")).containsExactly("2");
    }

    @Test
    void productsNeedTheirGroupFirst() {
        TenantId t = s.freshTenant("IMP_NOGROUP");
        assertThatThrownBy(() -> s.importJson(t, "v1.json", V1)).isInstanceOf(CatalogImportRejectedException.class)
                .hasMessageContaining("unknown group PG-HEALTH");
        assertThat(column(t, "SELECT count(*) FROM catalog_import")).containsExactly("0");
    }

    @ParameterizedTest(name = "invalid file {index}")
    @ValueSource(strings = {
            // 상품키 접두 ≠ insurerCode
            "{\"schemaVersion\":1,\"kind\":\"PRODUCTS\",\"source\":\"T\",\"asOf\":\"2026-09-01\",\"products\":[{\"productKey\":\"INS-A:P\",\"insurerCode\":\"INS-B\",\"groupCode\":\"PG-HEALTH\",\"productName\":\"x\",\"saleFrom\":\"2026-01-01\",\"saleTo\":null,\"defaults\":{}}]}",
            // 금액에 실수
            "{\"schemaVersion\":1,\"kind\":\"PRODUCTS\",\"source\":\"T\",\"asOf\":\"2026-09-01\",\"products\":[{\"productKey\":\"INS-A:P\",\"insurerCode\":\"INS-A\",\"groupCode\":\"PG-HEALTH\",\"productName\":\"x\",\"saleFrom\":\"2026-01-01\",\"saleTo\":null,\"defaults\":{\"PREMIUM_EXAMPLE_WON\":32100.5}}]}",
            // 같은 키 두 번
            "{\"schemaVersion\":1,\"kind\":\"PRODUCT_GROUPS\",\"source\":\"T\",\"asOf\":\"2026-09-01\",\"groups\":[{\"groupCode\":\"PG-X\",\"name\":\"x\",\"line\":\"LIFE\",\"applyFrom\":\"2026-01-01\",\"applyTo\":null},{\"groupCode\":\"PG-X\",\"name\":\"y\",\"line\":\"LIFE\",\"applyFrom\":\"2026-01-01\",\"applyTo\":null}]}",
            // 끝이 시작보다 이름
            "{\"schemaVersion\":1,\"kind\":\"PRODUCT_GROUPS\",\"source\":\"T\",\"asOf\":\"2026-09-01\",\"groups\":[{\"groupCode\":\"PG-X\",\"name\":\"x\",\"line\":\"LIFE\",\"applyFrom\":\"2026-02-01\",\"applyTo\":\"2026-01-01\"}]}",
            // 달력에 없는 날
            "{\"schemaVersion\":1,\"kind\":\"PRODUCT_GROUPS\",\"source\":\"T\",\"asOf\":\"2026-02-30\",\"groups\":[]}",
            // 중복 JSON 키(엄격 파싱)
            "{\"schemaVersion\":1,\"kind\":\"PRODUCT_GROUPS\",\"kind\":\"PRODUCTS\",\"source\":\"T\",\"asOf\":\"2026-09-01\",\"groups\":[]}",
            // 스키마: kind와 다른 배열, 모르는 속성
            "{\"schemaVersion\":1,\"kind\":\"PRODUCT_GROUPS\",\"source\":\"T\",\"asOf\":\"2026-09-01\",\"products\":[]}",
            "{\"schemaVersion\":1,\"kind\":\"PRODUCT_GROUPS\",\"source\":\"T\",\"asOf\":\"2026-09-01\",\"groups\":[],\"extra\":1}",
            // 기준일 없음(오늘로 채우지 않는다)
            "{\"schemaVersion\":1,\"kind\":\"PRODUCT_GROUPS\",\"source\":\"T\",\"groups\":[]}"})
    void invalidFilesAreRejectedBeforeAnythingIsWritten(String json) {
        TenantId t = withGroups();
        assertThatThrownBy(() -> s.importJson(t, "bad.json", json)).isInstanceOf(InvalidCatalogFileException.class);
        assertThat(column(t, "SELECT count(*) FROM catalog_import")).containsExactly("1");
    }

    /** DB가 두 번째 방어선: 카탈로그 행 삭제·TRUNCATE(GD070), 상품 키 정체성 변경(GD071), 수입 이력 수정(GD030). */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "GD070|DELETE FROM product_catalog WHERE tenant_id = current_setting('app.tenant_id')",
            "GD070|DELETE FROM product_group WHERE tenant_id = current_setting('app.tenant_id')",
            "GD070|DELETE FROM insurer_panel WHERE tenant_id = current_setting('app.tenant_id')",
            "GD071|UPDATE product_catalog SET insurer_code = 'INS-Z' WHERE tenant_id = current_setting('app.tenant_id')",
            "GD071|UPDATE product_catalog SET product_key = 'INS-A:OTHER' WHERE tenant_id = current_setting('app.tenant_id')",
            "GD030|UPDATE catalog_import SET as_of = DATE '2020-01-01' WHERE tenant_id = current_setting('app.tenant_id')",
            "GD030|DELETE FROM catalog_import WHERE tenant_id = current_setting('app.tenant_id')"})
    void databaseRefusesDeletionAndIdentityChanges(String caseSpec) {
        String[] parts = caseSpec.split("\\|", 2);
        String tenant = SeedData.uniqueTenant("IMP_GUARD");
        DB.seed(tenant, c -> SeedData.everyTable(c, tenant));
        assertThatThrownBy(() -> DB.asApp(tenant, c -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate(parts[1]);
            }
        })).isInstanceOf(PostgresHarness.UncheckedSqlException.class)
                .satisfies(e -> assertThat(((PostgresHarness.UncheckedSqlException) e).sqlState()).isEqualTo(parts[0]));
    }

    /** 스키마 소유자도 트리거에 묶인다: TRUNCATE(CASCADE 포함)는 행 단위 삭제 트리거를 우회하는 경로다. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GD070|product_group", "GD070|product_catalog", "GD070|insurer_panel", "GD030|catalog_import"})
    void ownerCannotTruncateCatalogTables(String caseSpec) {
        String[] parts = caseSpec.split("\\|", 2);
        String tenant = SeedData.uniqueTenant("IMP_TRUNC");
        DB.seed(tenant, c -> SeedData.everyTable(c, tenant));
        assertThat(TriggerAssertions.sqlStateOf(() -> DB.seed(tenant, c -> SeedData.exec(c, "TRUNCATE " + parts[1] + " CASCADE"))))
                .isEqualTo(parts[0]);
    }
}
