package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.infra.persistence.RuleVersionRecord;
import com.ga.disclosure.infra.persistence.RuleVersionRepository;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import com.ga.platform.spring.jdbc.TenantSessionBinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C9: 같은 scope에서 기간이 겹치는 ACTIVE rule_version INSERT 거부 — 개시일이 같은 경우와 다른 경우 모두,
 * apply_to NULL(무기한) 겹침 포함; DRAFT·APPROVED·RETIRED는 겹쳐도 허용. 저장소(TenantScopedRepository) 경로로 검증한다.
 */
class RuleVersionExclusionIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final AtomicInteger IDS = new AtomicInteger();

    private final RuleVersionRepository repository = new RuleVersionRepository(new TenantScopedRepository.Gateway(DB.appDataSource()));
    private final TransactionTemplate tx = new TransactionTemplate(new TenantSessionBinder(DB.appDataSource()));

    private static TenantId freshTenant() {
        String t = SeedData.uniqueTenant("RV");
        DB.seed(t, c -> SeedData.tenant(c, t));
        return TenantId.of(t);
    }

    private static RuleVersionRecord rule(String scope, String from, String to, RuleStatus status) {
        return new RuleVersionRecord(RuleVersionId.of("DISC-T-" + IDS.incrementAndGet()), scope,
                LocalDate.parse(from), to == null || to.isBlank() ? null : LocalDate.parse(to), status, null, null,
                "{\"minCompare\": 3}");
    }

    private void insert(TenantId tenant, RuleVersionRecord r) {
        TenantContext.runWith(tenant, () -> tx.executeWithoutResult(s -> repository.insert(r)));
    }

    private static String sqlStateOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException e) {
                return e.getSQLState();
            }
        }
        return null;
    }

    @ParameterizedTest(name = "existing [{0}, {1}) vs new [{2}, {3}) → overlap rejected")
    @CsvSource(nullValues = "∞", value = {
            // 개시일이 같은 경우
            "2026-07-01, 2027-01-01, 2026-07-01, 2027-01-01",
            "2026-07-01, ∞,          2026-07-01, 2026-12-31",
            // 개시일이 다른 경우(엔진에서 유니크 인덱스를 통과했던 틈)
            "2026-07-01, 2027-01-01, 2026-10-01, 2027-06-01",
            "2026-07-01, 2027-01-01, 2026-01-01, 2026-07-02",
            "2026-07-01, 2027-01-01, 2026-08-01, 2026-09-01",
            // apply_to NULL(무기한) 겹침
            "2026-07-01, ∞,          2030-01-01, ∞",
            "2026-07-01, 2027-01-01, 2025-01-01, ∞",
            "2026-07-01, ∞,          2020-01-01, 2026-07-02",
    })
    void overlappingActiveRejected(String existingFrom, String existingTo, String newFrom, String newTo) {
        TenantId tenant = freshTenant();
        insert(tenant, rule("GLOBAL", existingFrom, existingTo, RuleStatus.ACTIVE));
        assertThatThrownBy(() -> insert(tenant, rule("GLOBAL", newFrom, newTo, RuleStatus.ACTIVE)))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlStateOf(e)).isEqualTo("23P01"));
    }

    @ParameterizedTest(name = "existing [{0}, {1}) vs new [{2}, {3}) → allowed")
    @CsvSource(nullValues = "∞", value = {
            "2026-07-01, 2027-01-01, 2027-01-01, ∞",          // 인접(반개구간)
            "2026-07-01, 2027-01-01, 2025-01-01, 2026-07-01",
    })
    void adjacentActiveAllowed(String existingFrom, String existingTo, String newFrom, String newTo) {
        TenantId tenant = freshTenant();
        insert(tenant, rule("GLOBAL", existingFrom, existingTo, RuleStatus.ACTIVE));
        insert(tenant, rule("GLOBAL", newFrom, newTo, RuleStatus.ACTIVE));
    }

    @Test
    void nonActiveStatusesMayOverlap() {
        TenantId tenant = freshTenant();
        insert(tenant, rule("GLOBAL", "2026-07-01", null, RuleStatus.ACTIVE));
        insert(tenant, rule("GLOBAL", "2026-07-01", null, RuleStatus.DRAFT));
        insert(tenant, rule("GLOBAL", "2026-07-01", null, RuleStatus.DRAFT));
        insert(tenant, rule("GLOBAL", "2026-08-01", "2027-01-01", RuleStatus.APPROVED));
        insert(tenant, rule("GLOBAL", "2026-07-01", null, RuleStatus.RETIRED));
        insert(tenant, rule("GLOBAL", "2026-01-01", null, RuleStatus.RETIRED));
    }

    @Test
    void otherScopeAndOtherTenantMayOverlap() {
        TenantId tenant = freshTenant();
        TenantId other = freshTenant();
        insert(tenant, rule("GLOBAL", "2026-07-01", null, RuleStatus.ACTIVE));
        insert(tenant, rule("TENANT", "2026-07-01", null, RuleStatus.ACTIVE));
        insert(other, rule("GLOBAL", "2026-07-01", null, RuleStatus.ACTIVE));
    }

    @Test
    void activatingAnOverlappingRuleIsRejected() {
        TenantId tenant = freshTenant();
        insert(tenant, rule("GLOBAL", "2026-07-01", null, RuleStatus.ACTIVE));
        RuleVersionRecord approved = rule("GLOBAL", "2027-01-01", null, RuleStatus.APPROVED);
        insert(tenant, approved);
        assertThatThrownBy(() -> TenantContext.runWith(tenant, () -> tx.executeWithoutResult(s ->
                repository.updateStatus(approved.ruleVersionId(), RuleStatus.ACTIVE))))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(sqlStateOf(e)).isEqualTo("23P01"));
    }

    @Test
    void findActiveOnResolvesSingleRuleByHalfOpenInterval() {
        TenantId tenant = freshTenant();
        RuleVersionRecord y2026 = rule("GLOBAL", "2026-07-01", "2027-01-01", RuleStatus.ACTIVE);
        RuleVersionRecord y2027 = rule("GLOBAL", "2027-01-01", null, RuleStatus.ACTIVE);
        insert(tenant, y2026);
        insert(tenant, y2027);
        insert(tenant, rule("GLOBAL", "2026-01-01", null, RuleStatus.DRAFT));

        TenantContext.runWith(tenant, () -> tx.executeWithoutResult(s -> {
            assertThat(repository.findActiveOn("GLOBAL", LocalDate.parse("2026-06-30"))).isEmpty();
            assertThat(repository.findActiveOn("GLOBAL", LocalDate.parse("2026-12-31")))
                    .get().extracting(RuleVersionRecord::ruleVersionId).isEqualTo(y2026.ruleVersionId());
            assertThat(repository.findActiveOn("GLOBAL", LocalDate.parse("2027-01-01")))
                    .get().satisfies(r -> {
                        assertThat(r.ruleVersionId()).isEqualTo(y2027.ruleVersionId());
                        assertThat(r.applyTo()).isNull();
                        assertThat(r.bodyJson()).contains("minCompare");
                    });
            assertThat(repository.findActiveOn("TENANT", LocalDate.parse("2027-01-01"))).isEmpty();
        }));
    }
}
