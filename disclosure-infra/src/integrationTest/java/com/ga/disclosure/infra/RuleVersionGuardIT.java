package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 C4: rule_version 불변 트리거(V4) — scope 2 × status 4 × 컬럼군 매트릭스.
 * GLOBAL 복제본은 본문·적용 개시일·출처·해시가 항상 불변이고, TENANT는 DRAFT일 때만 수정 가능하다. apply_to는 NULL→값 1회,
 * status는 한 단계 전진만, 승인 기록은 DRAFT→APPROVED에서만, DELETE·TRUNCATE는 거부. 각 칸은 독립 테넌트에서 실행한다.
 */
class RuleVersionGuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String RULE = "R-GUARD";
    private static final List<String> SCOPES = List.of("GLOBAL", "TENANT");
    private static final List<String> STATUSES = List.of("DRAFT", "APPROVED", "ACTIVE", "RETIRED");
    private static final String WHERE = " WHERE tenant_id = ? AND rule_version_id = '" + RULE + "'";

    /** GLOBAL·DRAFT는 존재할 수 없으므로 7칸. */
    static Stream<Arguments> cells() {
        return SCOPES.stream().flatMap(scope -> STATUSES.stream()
                .filter(status -> !(scope.equals("GLOBAL") && status.equals("DRAFT")))
                .map(status -> Arguments.of(scope, status)));
    }

    private static String seed(String scope, String status, String applyToOrNull) {
        String tenant = SeedData.uniqueTenant("RVG");
        DB.seed(tenant, c -> SeedData.ruleVersion(c, tenant, RULE, scope, status, "2026-01-01", applyToOrNull));
        return tenant;
    }

    private static boolean editable(String scope, String status) {
        return scope.equals("TENANT") && status.equals("DRAFT");
    }

    // ------------------------------------------------------------------ INSERT

    @Test
    void globalRuleCanOnlyBeInsertedAsApprovedReplica() {
        for (String status : List.of("DRAFT", "ACTIVE", "RETIRED")) {
            String tenant = SeedData.uniqueTenant("RVG");
            assertThat(sqlStateOf(() -> DB.seed(tenant, c -> SeedData.exec(c, """
                    INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, status, approved_by, approved_at, body,
                                              source_bundle_id, bundle_hash)
                    VALUES (?, 'G', 'GLOBAL', DATE '2026-01-01', ?, 'X', now(), '{}'::jsonb, 'G@44136fa355b3', ?)
                    """, tenant, status, SeedData.EMPTY_OBJECT_HASH)))).as("GLOBAL inserted as %s", status).isEqualTo("GD040");
        }
    }

    @Test
    void tenantRuleCanOnlyBeInsertedAsDraft() {
        for (String status : List.of("APPROVED", "ACTIVE", "RETIRED")) {
            String tenant = SeedData.uniqueTenant("RVG");
            assertThat(sqlStateOf(() -> DB.seed(tenant, c -> SeedData.exec(c, """
                    INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, status, approved_by, approved_at, body)
                    VALUES (?, 'T', 'TENANT', DATE '2026-01-01', ?, 'X', now(), '{}'::jsonb)
                    """, tenant, status)))).as("TENANT inserted as %s", status).isEqualTo("GD040");
        }
    }

    @Test
    void bundleProvenanceIsRequiredForGlobalAndForbiddenForTenant() {
        String global = SeedData.uniqueTenant("RVG");
        assertThat(sqlStateOf(() -> DB.seed(global, c -> SeedData.exec(c, """
                INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, status, approved_by, approved_at, body)
                VALUES (?, 'G', 'GLOBAL', DATE '2026-01-01', 'APPROVED', 'X', now(), '{}'::jsonb)
                """, global)))).isEqualTo("23514");
        String tenant = SeedData.uniqueTenant("RVG");
        assertThat(sqlStateOf(() -> DB.seed(tenant, c -> SeedData.exec(c, """
                INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, status, body, source_bundle_id, bundle_hash)
                VALUES (?, 'T', 'TENANT', DATE '2026-01-01', 'DRAFT', '{}'::jsonb, 'T@44136fa355b3', ?)
                """, tenant, SeedData.EMPTY_OBJECT_HASH)))).isEqualTo("23514");
        assertThat(sqlStateOf(() -> DB.seed(global, c -> SeedData.exec(c, """
                INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, status, approved_by, approved_at, body,
                                          source_bundle_id, bundle_hash)
                VALUES (?, 'G', 'GLOBAL', DATE '2026-01-01', 'APPROVED', 'X', now(), '{}'::jsonb, 'G@1', 'NOT-A-HASH')
                """, global)))).as("bundle_hash format").isEqualTo("23514");
    }

    // ------------------------------------------------------------------ 본문 컬럼군

    static Stream<Arguments> cellsTimesBodyColumns() {
        List<String> sets = List.of(
                "body = '{\"minCompare\": 9}'::jsonb",
                "apply_from = DATE '2025-12-01'");
        return cells().flatMap(cell -> sets.stream().map(set -> Arguments.of(cell.get()[0], cell.get()[1], set)));
    }

    @ParameterizedTest(name = "{0} {1} SET {2}")
    @MethodSource("cellsTimesBodyColumns")
    void bodyAndApplyFromAreEditableOnlyForTenantDraft(String scope, String status, String set) {
        String tenant = seed(scope, status, null);
        String sql = "UPDATE rule_version SET " + set + WHERE;
        if (editable(scope, status)) {
            assertAllowed(DB, tenant, sql, tenant);
        } else {
            assertRejected(DB, tenant, "GD041", sql, tenant);
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cells")
    void bundleProvenanceNeverChanges(String scope, String status) {
        String tenant = seed(scope, status, null);
        String sql = "UPDATE rule_version SET source_bundle_id = 'X@000000000000', bundle_hash = repeat('a', 64)" + WHERE;
        // TENANT·DRAFT는 트리거를 통과하지만 "TENANT는 번들 출처 없음" CHECK가 막는다.
        assertRejected(DB, tenant, editable(scope, status) ? "23514" : "GD041", sql, tenant);
        if (scope.equals("GLOBAL")) {
            assertRejected(DB, tenant, "GD041", "UPDATE rule_version SET bundle_hash = repeat('b', 64)" + WHERE, tenant);
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cells")
    void identityAndScopeNeverChange(String scope, String status) {
        String tenant = seed(scope, status, null);
        String other = scope.equals("GLOBAL") ? "TENANT" : "GLOBAL";
        assertRejected(DB, tenant, "GD041", "UPDATE rule_version SET scope = '" + other + "'" + WHERE, tenant);
        assertRejected(DB, tenant, "GD041", "UPDATE rule_version SET rule_version_id = 'R-OTHER'" + WHERE, tenant);
    }

    // ------------------------------------------------------------------ apply_to (NULL → 값 1회)

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cells")
    void applyToCanBeWrittenOnceFromNull(String scope, String status) {
        String open = seed(scope, status, null);
        assertAllowed(DB, open, "UPDATE rule_version SET apply_to = DATE '2030-01-01'" + WHERE, open);

        String closed = seed(scope, status, "2030-01-01");
        assertRejected(DB, closed, "GD042", "UPDATE rule_version SET apply_to = DATE '2031-01-01'" + WHERE, closed);
        assertRejected(DB, closed, "GD042", "UPDATE rule_version SET apply_to = NULL" + WHERE, closed);
    }

    @Test
    void applyToMustBeAfterApplyFrom() {
        String tenant = seed("GLOBAL", "ACTIVE", null);
        assertRejected(DB, tenant, "23514", "UPDATE rule_version SET apply_to = DATE '2026-01-01'" + WHERE, tenant);
    }

    // ------------------------------------------------------------------ status (한 단계 전진만)

    static Stream<Arguments> cellsTimesTargets() {
        return cells().flatMap(cell -> STATUSES.stream()
                .filter(target -> !target.equals(cell.get()[1]))
                .map(target -> Arguments.of(cell.get()[0], cell.get()[1], target)));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("cellsTimesTargets")
    void statusMovesOnlyOneStepForward(String scope, String status, String target) {
        String tenant = seed(scope, status, null);
        boolean next = STATUSES.indexOf(target) == STATUSES.indexOf(status) + 1;
        String sql = target.equals("APPROVED")
                ? "UPDATE rule_version SET status = 'APPROVED', approved_by = 'COMPLIANCE:x', approved_at = now()" + WHERE
                : "UPDATE rule_version SET status = '" + target + "'" + WHERE;
        if (next) {
            assertAllowed(DB, tenant, sql, tenant);
        } else {
            assertRejected(DB, tenant, "GD043", sql, tenant);
        }
    }

    @Test
    void approvalRequiresApproverAndTimestamp() {
        String tenant = seed("TENANT", "DRAFT", null);
        assertRejected(DB, tenant, "23514", "UPDATE rule_version SET status = 'APPROVED'" + WHERE, tenant);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cells")
    void approvalRecordChangesOnlyOnDraftToApproved(String scope, String status) {
        String tenant = seed(scope, status, null);
        String sql = "UPDATE rule_version SET approved_by = 'SOMEONE-ELSE'" + WHERE;
        // DRAFT에서 상태 전이 없이 승인자만 쓰는 것도 거부(GD044).
        assertRejected(DB, tenant, "GD044", sql, tenant);
    }

    // ------------------------------------------------------------------ DELETE·TRUNCATE

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cells")
    void rowsAreNeverDeleted(String scope, String status) {
        String tenant = seed(scope, status, null);
        assertRejected(DB, tenant, "GD045", "DELETE FROM rule_version" + WHERE, tenant);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rule_version", "form_template"})
    void ownerCannotTruncateRuleData(String table) {
        String tenant = seed("GLOBAL", "ACTIVE", null);
        assertThat(sqlStateOf(() -> DB.seed(tenant, c -> SeedData.exec(c, "TRUNCATE " + table)))).isEqualTo("GD045");
    }

    @Test
    void ownerIsBoundByTheGuardToo() {
        String tenant = seed("GLOBAL", "ACTIVE", null);
        assertThat(sqlStateOf(() -> DB.seed(tenant, c -> SeedData.exec(c,
                "UPDATE rule_version SET body = '{\"minCompare\": 1}'::jsonb" + WHERE, tenant)))).isEqualTo("GD041");
    }
}
