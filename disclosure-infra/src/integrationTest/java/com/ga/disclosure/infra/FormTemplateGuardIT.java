package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1: form_template 불변 트리거(V4). 번들 출처 템플릿은 불변(apply_to만 NULL→값 1회). 테넌트 작성본은 status가 없으므로
 * "적용 개시 전(apply_from > 오늘, Asia/Seoul)"을 미승인으로 보고 그동안만 수정할 수 있다 — Phase 1 지시문의 해석(보고서에 기록).
 * 같은 서식 유형의 적용 구간 겹침은 배타 제약이 막는다.
 */
class FormTemplateGuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String WHERE = " WHERE tenant_id = ? AND template_id = 'STANDARD' AND version = 1";

    private static String seed(String applyFrom, String applyToOrNull, String bundleIdOrNull) {
        String tenant = SeedData.uniqueTenant("FTG");
        DB.seed(tenant, c -> SeedData.formTemplate(c, tenant, "STANDARD", 1, applyFrom, applyToOrNull, bundleIdOrNull));
        return tenant;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "fields = '[{\"code\":\"X\"}]'::jsonb",
            "layout = '{\"sections\":[]}'::jsonb",
            "pending_confirmation = '[{\"ref\":\"TODO(confirm#2)\",\"note\":\"x\"}]'::jsonb",
            "apply_from = DATE '2026-08-01'",
            "template_type = 'AUTO'"})
    void bundleTemplateBodyIsImmutable(String set) {
        String tenant = seed("2026-07-01", null, "STANDARD.v1@44136fa355b3");
        assertRejected(DB, tenant, "GD050", "UPDATE form_template SET " + set + WHERE, tenant);
    }

    @Test
    void bundleProvenanceAndIdentityNeverChange() {
        String bundle = seed("2026-07-01", null, "STANDARD.v1@44136fa355b3");
        assertRejected(DB, bundle, "GD050", "UPDATE form_template SET bundle_hash = repeat('c', 64)" + WHERE, bundle);
        assertRejected(DB, bundle, "GD050", "UPDATE form_template SET version = 2" + WHERE, bundle);
        String own = seed("2099-01-01", null, null);
        assertRejected(DB, own, "GD050",
                "UPDATE form_template SET source_bundle_id = 'X@1', bundle_hash = repeat('c', 64)" + WHERE, own);
    }

    @Test
    void tenantTemplateIsEditableUntilItTakesEffect() {
        String future = seed("2099-01-01", null, null);
        assertAllowed(DB, future, "UPDATE form_template SET fields = '[{\"code\":\"X\"}]'::jsonb" + WHERE, future);
        assertAllowed(DB, future, "UPDATE form_template SET apply_from = DATE '2098-01-01'" + WHERE, future);

        String effective = seed("2026-01-01", null, null);
        assertRejected(DB, effective, "GD051", "UPDATE form_template SET fields = '[{\"code\":\"X\"}]'::jsonb" + WHERE, effective);
    }

    @Test
    void applyToIsWriteOnce() {
        String open = seed("2026-07-01", null, "STANDARD.v1@44136fa355b3");
        assertAllowed(DB, open, "UPDATE form_template SET apply_to = DATE '2027-01-01'" + WHERE, open);

        String closed = seed("2026-07-01", "2027-01-01", "STANDARD.v1@44136fa355b3");
        assertRejected(DB, closed, "GD052", "UPDATE form_template SET apply_to = DATE '2028-01-01'" + WHERE, closed);
        assertRejected(DB, closed, "GD052", "UPDATE form_template SET apply_to = NULL" + WHERE, closed);
    }

    @Test
    void rowsAreNeverDeleted() {
        String tenant = seed("2099-01-01", null, null);
        assertRejected(DB, tenant, "GD045", "DELETE FROM form_template" + WHERE, tenant);
    }

    @Test
    void overlappingTemplatesOfTheSameTypeAreRejected() {
        String tenant = seed("2026-07-01", null, "STANDARD.v1@44136fa355b3");
        assertThat(sqlStateOf(() -> DB.seed(tenant, c ->
                SeedData.formTemplate(c, tenant, "STANDARD", 2, "2026-12-01", null, "STANDARD.v2@44136fa355b3"))))
                .isEqualTo("23P01");
        // 선행 템플릿을 닫으면 이어지는 구간은 허용된다.
        DB.seed(tenant, c -> {
            SeedData.exec(c, "UPDATE form_template SET apply_to = DATE '2027-01-01'" + WHERE, tenant);
            SeedData.formTemplate(c, tenant, "STANDARD", 2, "2027-01-01", null, "STANDARD.v2@44136fa355b3");
        });
    }

    @Test
    void unknownTemplateTypeIsRejected() {
        String tenant = SeedData.uniqueTenant("FTG");
        assertThat(sqlStateOf(() -> DB.seed(tenant, c -> SeedData.exec(c, """
                INSERT INTO form_template (tenant_id, template_id, template_type, version, apply_from, fields, layout)
                VALUES (?, 'X', 'LIFE', 1, DATE '2026-07-01', '[]'::jsonb, '{}'::jsonb)
                """, tenant)))).isEqualTo("23514");
    }
}
