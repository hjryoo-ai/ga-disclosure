package com.ga.disclosure.infra;

import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.workflow.catalog.CatalogProduct;
import com.ga.disclosure.workflow.catalog.PanelEntry;
import com.ga.disclosure.workflow.catalog.ProductGroup;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.ga.disclosure.infra.CatalogFiles.group;
import static com.ga.disclosure.infra.CatalogFiles.groups;
import static com.ga.disclosure.infra.CatalogFiles.insurer;
import static com.ga.disclosure.infra.CatalogFiles.panel;
import static com.ga.disclosure.infra.CatalogFiles.product;
import static com.ga.disclosure.infra.CatalogFiles.products;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 P1: 상품군·상품·보험사 패널은 기준일로만 조회되고 유효기간은 반개구간 [from, to)다 — 경계일 양쪽(from−1, from, to−1, to).
 * 빈 구간 [x, x)는 어느 날에도 보이지 않는다. 기준일은 필수(기본값 없음), 다른 테넌트로는 부를 수 없다.
 */
class CatalogAsOfIT {

    private static final CatalogCustomerSetup S = new CatalogCustomerSetup();
    private static TenantId t;

    @BeforeAll
    static void importCatalog() {
        t = S.freshTenant("CAT");
        S.importJson(t, "groups.json", groups("2026-09-01",
                group("PG-HEALTH-SIMPLE", "2026-01-01", "2027-01-01"),
                group("PG-CANCER", "2026-07-01", null)));
        S.importJson(t, "products.json", products("2026-09-01",
                product("INS-A:PRD-1001", "PG-HEALTH-SIMPLE", "(가상) 간편 건강 100%_A", "2026-03-01", "2026-10-01"),
                product("INS-B:PRD-2001", "PG-HEALTH-SIMPLE", "(가상) 간편 건강 B", "2026-01-01", null),
                product("INS-C:PRD-3001", "PG-CANCER", "(가상) 암보험 C", "2026-07-01", null),
                product("INS-D:PRD-4001", "PG-HEALTH-SIMPLE", "(가상) 철회된 상품", "2026-11-01", "2026-11-01")));
        S.importJson(t, "panel.json", panel("2026-09-01",
                insurer("INS-A", "2026-01-01", "2026-12-01"),
                insurer("INS-B", "2026-01-01", null),
                insurer("INS-A", "2027-01-01", null)));
    }

    private static LocalDate d(String date) {
        return LocalDate.parse(date);
    }

    @ParameterizedTest(name = "product INS-A:PRD-1001 on {0} → {1}")
    @CsvSource({"2026-02-28, false", "2026-03-01, true", "2026-09-30, true", "2026-10-01, false"})
    void productSaleWindowIsHalfOpen(String date, boolean visible) {
        Optional<CatalogProduct> p = S.in(t, () -> S.catalog.getProduct(t, ProductKey.parse("INS-A:PRD-1001"), d(date)));
        assertThat(p.isPresent()).isEqualTo(visible);
        List<String> listed = S.in(t, () -> S.catalog.searchProducts(t, GroupCode.of("PG-HEALTH-SIMPLE"), Optional.empty(), "", d(date)))
                .stream().map(c -> c.key().value()).toList();
        assertThat(listed.contains("INS-A:PRD-1001")).isEqualTo(visible);
    }

    @ParameterizedTest(name = "group PG-HEALTH-SIMPLE on {0} → {1}")
    @CsvSource({"2025-12-31, false", "2026-01-01, true", "2026-12-31, true", "2027-01-01, false"})
    void groupWindowIsHalfOpen(String date, boolean visible) {
        List<String> groups = S.in(t, () -> S.catalog.listGroups(t, d(date))).stream().map(g -> g.code().value()).toList();
        assertThat(groups.contains("PG-HEALTH-SIMPLE")).isEqualTo(visible);
    }

    @ParameterizedTest(name = "INS-A on panel on {0} → {1}")
    @CsvSource({"2025-12-31, false", "2026-01-01, true", "2026-11-30, true", "2026-12-01, false", "2026-12-31, false",
            "2027-01-01, true"})
    void panelWindowsAreHalfOpenAndMayResume(String date, boolean onPanel) {
        assertThat(S.in(t, () -> S.catalog.isOnPanel(t, InsurerCode.of("INS-A"), d(date)))).isEqualTo(onPanel);
        List<String> panel = S.in(t, () -> S.catalog.panel(t, d(date))).stream().map(PanelEntry::insurer).map(InsurerCode::value).toList();
        assertThat(panel.contains("INS-A")).isEqualTo(onPanel);
    }

    @Test
    void emptyWindowIsNeverVisible() {
        for (String date : List.of("2026-10-31", "2026-11-01", "2026-11-02")) {
            assertThat(S.in(t, () -> S.catalog.getProduct(t, ProductKey.parse("INS-D:PRD-4001"), d(date)))).as(date).isEmpty();
        }
    }

    @Test
    void searchFiltersByInsurerAndKeywordWithLikeCharactersEscaped() {
        GroupCode group = GroupCode.of("PG-HEALTH-SIMPLE");
        LocalDate day = d("2026-09-23");
        assertThat(S.in(t, () -> S.catalog.searchProducts(t, group, Optional.of(InsurerCode.of("INS-B")), " ", day)))
                .extracting(c -> c.key().value()).containsExactly("INS-B:PRD-2001");
        assertThat(S.in(t, () -> S.catalog.searchProducts(t, group, Optional.empty(), "100%", day)))
                .extracting(c -> c.key().value()).containsExactly("INS-A:PRD-1001");
        assertThat(S.in(t, () -> S.catalog.searchProducts(t, group, Optional.empty(), "%", day)))
                .extracting(c -> c.key().value()).as("% is literal").containsExactly("INS-A:PRD-1001");
        assertThat(S.in(t, () -> S.catalog.searchProducts(t, group, Optional.empty(), "건강%B", day)))
                .as("a wildcard % would have matched '건강 B'").isEmpty();
        assertThat(S.in(t, () -> S.catalog.searchProducts(t, group, Optional.empty(), "_", day)))
                .extracting(c -> c.key().value()).as("_ is literal").containsExactly("INS-A:PRD-1001");
        assertThat(S.in(t, () -> S.catalog.getProduct(t, ProductKey.parse("INS-A:PRD-1001"), day))).hasValueSatisfying(p -> {
            assertThat(p.defaults().path("PREMIUM_EXAMPLE_WON").asLong()).isEqualTo(32100L);
            assertThat(p.group().value()).isEqualTo("PG-HEALTH-SIMPLE");
        });
    }

    @Test
    void asOfIsMandatoryAndTheBoundTenantMustMatch() {
        assertThatThrownBy(() -> S.in(t, () -> S.catalog.listGroups(t, null))).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("asOf");
        TenantId other = S.freshTenant("CAT_OTHER");
        assertThatThrownBy(() -> S.in(t, () -> S.catalog.isOnPanel(other, InsurerCode.of("INS-A"), d("2026-09-23"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(S.in(other, () -> S.catalog.listGroups(other, d("2026-09-23")))).as("RLS: another tenant sees nothing").isEmpty();
    }

    /** 같은 보험사의 위탁 기간은 겹칠 수 없다(DB 배타 제약) — 기준일 판정이 단건이다. */
    @Test
    void overlappingPanelWindowsAreRejectedByTheDatabase() {
        TenantId x = S.freshTenant("CAT_OVERLAP");
        assertThatThrownBy(() -> S.importJson(x, "panel.json",
                panel("2026-09-01", insurer("INS-A", "2026-01-01", null), insurer("INS-A", "2026-06-01", null))))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .rootCause().satisfies(e -> assertThat(((org.postgresql.util.PSQLException) e).getSQLState()).isEqualTo("23P01"));
        assertThat(S.in(x, () -> S.catalog.panel(x, d("2026-09-23")))).as("the whole import rolled back").isEmpty();
    }

    @Test
    void groupRecordsCarryTheirLineAndWindow() {
        List<ProductGroup> groups = S.in(t, () -> S.catalog.listGroups(t, d("2026-09-23")));
        assertThat(groups).extracting(g -> g.code().value()).containsExactly("PG-CANCER", "PG-HEALTH-SIMPLE");
        assertThat(groups.get(1).applyTo()).isEqualTo(d("2027-01-01"));
    }
}
