package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.infra.persistence.RuleVersionRepository;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantSessionBinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 0 C9 / Phase 1 C2: 같은 scope에서 기준일에 시행 중인 룰(ACTIVE·RETIRED)의 기간 겹침은 DB 배타 제약이 거부한다 —
 * 개시일이 같은 경우와 다른 경우, apply_to NULL(무기한) 겹침 포함. DRAFT·APPROVED는 겹쳐도 된다.
 * V4부터 룰은 ACTIVE로 삽입될 수 없으므로(GLOBAL은 APPROVED, TENANT는 DRAFT로만) 겹침은 <b>활성화 UPDATE</b>에서 잡힌다.
 * 해석용 조회({@code findActive})는 ACTIVE와 RETIRED를 함께 보며, 배타 제약 때문에 한 기준일에 2건이 나올 수 없다.
 */
class RuleVersionExclusionIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final AtomicInteger IDS = new AtomicInteger();

    private final RuleVersionRepository repository = new RuleVersionRepository(new TenantJdbcGateway(DB.appDataSource()));
    private final TransactionTemplate tx = new TransactionTemplate(new TenantSessionBinder(DB.appDataSource()));

    private static String freshTenant() {
        String t = SeedData.uniqueTenant("RV");
        DB.seed(t, c -> SeedData.tenant(c, t));
        return t;
    }

    /** 정상 경로로 {@code status}까지 전진시킨 룰. 반환값은 룰 ID. */
    private static String rule(String tenant, String scope, String status, String from, String to) {
        String id = "DISC-T-" + IDS.incrementAndGet();
        DB.seed(tenant, c -> SeedData.ruleVersion(c, tenant, id, scope, status, from, to));
        return id;
    }

    /** APPROVED(GLOBAL) 룰을 만들고 ACTIVE로 올리는 UPDATE의 SQLSTATE(성공이면 null). */
    private static String activate(String tenant, String from, String to) {
        String id = rule(tenant, "GLOBAL", "APPROVED", from, to);
        try {
            DB.seed(tenant, c -> SeedData.exec(c,
                    "UPDATE rule_version SET status = 'ACTIVE' WHERE tenant_id = ? AND rule_version_id = ?", tenant, id));
            return null;
        } catch (PostgresHarness.UncheckedSqlException e) {
            return e.sqlState();
        }
    }

    @ParameterizedTest(name = "existing {4} [{0}, {1}) vs new [{2}, {3}) → overlap rejected")
    @CsvSource(nullValues = "∞", value = {
            // 개시일이 같은 경우
            "2026-07-01, 2027-01-01, 2026-07-01, 2027-01-01, ACTIVE",
            "2026-07-01, ∞,          2026-07-01, 2026-12-31, ACTIVE",
            // 개시일이 다른 경우(엔진에서 유니크 인덱스를 통과했던 틈)
            "2026-07-01, 2027-01-01, 2026-10-01, 2027-06-01, ACTIVE",
            "2026-07-01, 2027-01-01, 2026-01-01, 2026-07-02, ACTIVE",
            "2026-07-01, 2027-01-01, 2026-08-01, 2026-09-01, ACTIVE",
            // apply_to NULL(무기한) 겹침
            "2026-07-01, ∞,          2030-01-01, ∞,          ACTIVE",
            "2026-07-01, 2027-01-01, 2025-01-01, ∞,          ACTIVE",
            "2026-07-01, ∞,          2020-01-01, 2026-07-02, ACTIVE",
            // V4: RETIRED 구간과 겹치는 활성화도 거부 — 과거 기준일의 해석이 사후에 바뀌지 않는다
            "2026-07-01, 2027-01-01, 2026-12-01, ∞,          RETIRED",
            "2026-07-01, 2027-01-01, 2026-01-01, 2026-08-01, RETIRED",
    })
    void overlappingInForceRejected(String existingFrom, String existingTo, String newFrom, String newTo, String existingStatus) {
        String tenant = freshTenant();
        rule(tenant, "GLOBAL", existingStatus, existingFrom, existingTo);
        assertThat(activate(tenant, newFrom, newTo)).isEqualTo("23P01");
    }

    @ParameterizedTest(name = "existing {4} [{0}, {1}) vs new [{2}, {3}) → allowed")
    @CsvSource(nullValues = "∞", value = {
            "2026-07-01, 2027-01-01, 2027-01-01, ∞,          ACTIVE",   // 인접(반개구간)
            "2026-07-01, 2027-01-01, 2025-01-01, 2026-07-01, ACTIVE",
            "2026-07-01, 2027-01-01, 2027-01-01, ∞,          RETIRED",
    })
    void adjacentAllowed(String existingFrom, String existingTo, String newFrom, String newTo, String existingStatus) {
        String tenant = freshTenant();
        rule(tenant, "GLOBAL", existingStatus, existingFrom, existingTo);
        assertThat(activate(tenant, newFrom, newTo)).isNull();
    }

    @Test
    void draftAndApprovedMayOverlap() {
        String tenant = freshTenant();
        rule(tenant, "GLOBAL", "ACTIVE", "2026-07-01", null);
        rule(tenant, "GLOBAL", "APPROVED", "2026-07-01", null);
        rule(tenant, "GLOBAL", "APPROVED", "2026-08-01", "2027-01-01");
        rule(tenant, "TENANT", "ACTIVE", "2026-07-01", null);
        rule(tenant, "TENANT", "DRAFT", "2026-07-01", null);
        rule(tenant, "TENANT", "DRAFT", "2026-07-01", null);
    }

    @Test
    void otherScopeAndOtherTenantMayOverlap() {
        String tenant = freshTenant();
        String other = freshTenant();
        rule(tenant, "GLOBAL", "ACTIVE", "2026-07-01", null);
        rule(tenant, "TENANT", "ACTIVE", "2026-07-01", null);
        rule(other, "GLOBAL", "ACTIVE", "2026-07-01", null);
    }

    @Test
    void findActiveReturnsRulesInForceOnTheDateIncludingRetired() {
        String tenant = freshTenant();
        String y2026 = rule(tenant, "GLOBAL", "RETIRED", "2026-07-01", "2027-01-01");
        String y2027 = rule(tenant, "GLOBAL", "ACTIVE", "2027-01-01", null);
        rule(tenant, "GLOBAL", "APPROVED", "2028-01-01", null);                     // 활성화 전: 해석 대상 아님
        String house = rule(tenant, "TENANT", "ACTIVE", "2026-07-01", null);
        TenantId t = TenantId.of(tenant);

        TenantContext.runWith(t, () -> tx.executeWithoutResult(s -> {
            assertThat(ids(repository.findActive(t, RuleScope.GLOBAL, LocalDate.parse("2026-06-30")))).isEmpty();
            assertThat(ids(repository.findActive(t, RuleScope.GLOBAL, LocalDate.parse("2026-12-31")))).containsExactly(y2026);
            assertThat(ids(repository.findActive(t, RuleScope.GLOBAL, LocalDate.parse("2027-01-01")))).containsExactly(y2027);
            assertThat(ids(repository.findActive(t, RuleScope.GLOBAL, LocalDate.parse("2028-06-01")))).containsExactly(y2027);
            assertThat(ids(repository.findActive(t, RuleScope.TENANT, LocalDate.parse("2027-01-01")))).containsExactly(house);
            assertThat(repository.findActive(t, RuleScope.GLOBAL, LocalDate.parse("2027-01-01")).getFirst())
                    .satisfies(r -> {
                        assertThat(r.applyTo()).isNull();
                        assertThat(r.sourceBundleId()).isEqualTo(y2027 + "@44136fa355b3");
                        assertThat(r.bundleHash()).isEqualTo(SeedData.EMPTY_OBJECT_HASH);
                    });
        }));
    }

    @Test
    void portRefusesAnotherTenantThanTheBoundOne() {
        String tenant = freshTenant();
        String other = freshTenant();
        TenantContext.runWith(TenantId.of(tenant), () -> tx.executeWithoutResult(s ->
                assertThatThrownBy(() ->
                                repository.findActive(TenantId.of(other), RuleScope.GLOBAL, LocalDate.parse("2027-01-01")))
                        .isInstanceOf(IllegalArgumentException.class)));
    }

    private static List<String> ids(List<RuleVersion> rules) {
        return rules.stream().map(r -> r.id().value()).toList();
    }
}
