package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.testing.TestItem;
import com.ga.disclosure.rules.testing.TestSubject;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.standard.StandardValidations;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.ga.disclosure.infra.CatalogFiles.insurer;
import static com.ga.disclosure.infra.CatalogFiles.panel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 지시문 §2: 검증 규칙 R-PANEL({@code ValidationSubject.isInsurerOnPanel})이 DB의 보험사 패널을 쓰는 함수
 * ({@code InsurerPanelPort.lookupFor(tenant)})로 연결된다. 확인서 애그리게이트는 Phase 3이므로 테스트 픽스처 주체가 그 함수를 받는다.
 * 위탁이 끝난 보험사는 경계일부터 R-PANEL 실패.
 */
class PanelValidationIT {

    private static final EffectiveRule RULE = RuleResolver.merge(LocalDate.parse("2026-09-23"),
            Bundles.global(Bundles.rule(Bundles.DISC_2026_07), RuleStatus.ACTIVE, null), null);
    private static final TemplateResolution TEMPLATE =
            TemplateResolver.resolution(Bundles.template(Bundles.template(Bundles.STANDARD_V1), null));

    private final CatalogCustomerSetup s = new CatalogCustomerSetup();

    private ValidationResult panelCheck(TenantId t, String consultDate) {
        return s.in(t, () -> {
            TestSubject subject = TestSubject.of("PG-HEALTH", LocalDate.parse(consultDate), List.of(
                            TestItem.of("INS-A", "PRD-1", "PG-HEALTH").recommended("PREMIUM"),
                            TestItem.of("INS-B", "PRD-2", "PG-HEALTH").recommended("COVERAGE"),
                            TestItem.of("INS-C", "PRD-3", "PG-HEALTH")))
                    .withPanel(s.catalog.lookupFor(t));
            return StandardValidations.registry().run(ValidationStage.COMPARE, subject, RULE, TEMPLATE).stream()
                    .filter(r -> r.ruleId().equals("R-PANEL")).findFirst().orElseThrow();
        });
    }

    @Test
    void panelRuleReadsTheImportedPanelOnTheConsultDate() {
        TenantId t = s.freshTenant("PANEL");
        s.importJson(t, "panel.json", panel("2026-09-01",
                insurer("INS-A", "2026-01-01", null), insurer("INS-B", "2026-01-01", null), insurer("INS-C", "2026-01-01", "2026-12-01")));
        assertThat(panelCheck(t, "2026-11-30").passed()).isTrue();
        ValidationResult afterContractEnds = panelCheck(t, "2026-12-01");
        assertThat(afterContractEnds.passed()).isFalse();
        assertThat(afterContractEnds.message()).contains("INS-C");
        assertThat(panelCheck(s.freshTenant("PANEL_EMPTY"), "2026-11-30").passed()).as("another tenant's panel is not visible").isFalse();
    }
}
