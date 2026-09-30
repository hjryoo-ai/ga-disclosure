package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.disclosure.workflow.disclosure.CommandResult;
import com.ga.disclosure.workflow.disclosure.ItemInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3A W6: 룰·서식 버전은 초안 생성 때 상담일로 한 번 해석해 고정되고, 이후 룰 데이터가 바뀌어도(사규 신설, 상담일에 걸치는 서식 새 버전
 * 배포) 그 확인서에는 영향이 없다. 경계일 2026-12-31(DISC-2026-07, 최소 3)·2027-01-01(DISC-2027-01, 최소 4) 두 초안으로 확인한다.
 */
class RuleFreezeIT {

    private static final LocalDate EVE = LocalDate.parse("2026-12-31");
    private static final LocalDate NEW_YEAR = LocalDate.parse("2027-01-01");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WorkflowSetup s = new WorkflowSetup();

    @AfterEach
    void stop() {
        s.close();
    }

    private DisclosureId draftOn(LocalDate consult) {
        return s.service.createDraft(s.tenant, WorkflowSetup.AGENT, s.customer, WorkflowSetup.GROUP, consult, TemplateType.STANDARD);
    }

    private String pinned(DisclosureId id) {
        return s.db.asApp(s.tenant.value(), c -> {
            try (var ps = c.prepareStatement("""
                    SELECT rule_version_id || '|' || coalesce(tenant_rule_version_id, '-') || '|' || template_id || ' v' || template_version
                      FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?""")) {
                ps.setString(1, s.tenant.value());
                ps.setObject(2, id.value());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    private static List<ItemInput> four() {
        return List.of(WorkflowSetup.catalogItem("INS-A:PRD-1001", true), WorkflowSetup.catalogItem("INS-B:PRD-2044", false),
                WorkflowSetup.catalogItem("INS-C:PRD-3120", true), WorkflowSetup.catalogItem("INS-E:PRD-5001", false));
    }

    @Test
    void boundaryDraftsPinTheirOwnVersionsAndIgnoreLaterRuleData() {
        DisclosureId eve = draftOn(EVE);
        DisclosureId newYear = draftOn(NEW_YEAR);
        assertThat(pinned(eve)).isEqualTo("DISC-2026-07|-|STANDARD v1");
        assertThat(pinned(newYear)).isEqualTo("DISC-2027-01|-|STANDARD v1");

        // 룰 데이터 변경 ① 상담일에 걸치는 서식 새 버전(v2, 2027-01-01부터, v1을 닫는다) ② 2026-07-01부터 적용되는 사규(HOUSE)
        Governance g = new Governance();
        g.distribution.distribute(BundleFiles.fixture("templates/STANDARD-v2.bundle.json"), s.tenant, Governance.OPERATOR);
        s.in(() -> {
            s.rules.insert(new RuleVersion(RuleVersionId.of("HOUSE-2026"), RuleScope.TENANT, LocalDate.parse("2026-07-01"), null,
                    RuleStatus.DRAFT, null, null, JSON.readTree("{\"signDeadlineDays\": 10}"), null, null));
            return null;
        });
        g.approval.approve(s.tenant, RuleVersionId.of("HOUSE-2026"), Governance.OPERATOR);
        new Governance("2027-01-01T00:00:00Z").activation.run(s.tenant, Governance.OPERATOR);
        assertThat(s.in(() -> new RuleResolver(s.rules).resolve(s.tenant, EVE).tenantRuleVersion())).as("새 해석은 사규를 본다")
                .contains(RuleVersionId.of("HOUSE-2026"));

        // 새 초안은 새 데이터를, 기존 초안은 고정 버전을 쓴다
        DisclosureId later = draftOn(NEW_YEAR);
        assertThat(pinned(later)).isEqualTo("DISC-2027-01|HOUSE-2026|STANDARD v2");
        assertThat(pinned(eve)).isEqualTo("DISC-2026-07|-|STANDARD v1");
        assertThat(pinned(newYear)).isEqualTo("DISC-2027-01|-|STANDARD v1");

        // 경계: 3개 항목은 2026-12-31 초안(최소 3)만 통과, 2027-01-01 초안(최소 4)은 거부
        s.service.replaceItems(s.tenant, WorkflowSetup.AGENT, eve, WorkflowSetup.threeItems());
        s.service.replaceItems(s.tenant, WorkflowSetup.AGENT, newYear, WorkflowSetup.threeItems());
        assertThat(s.service.compare(s.tenant, WorkflowSetup.AGENT, eve).status()).isEqualTo(DisclosureStatus.COMPARED);
        CommandResult blocked = s.service.compare(s.tenant, WorkflowSetup.AGENT, newYear);
        assertThat(blocked.rejectionOrNull()).isEqualTo(CommandResult.Rejection.VALIDATION_BLOCKED);
        assertThat(blocked.results()).filteredOn(ValidationResult::blocking).extracting(ValidationResult::ruleId).containsExactly("R-MIN-COMPARE");

        // 서식: 고정된 v1과 새 초안의 v2(필수 항목 TEST_ONLY_FIELD 추가)는 같은 항목에서 다른 필수 목록으로 검증된다(산출 전이라 둘 다 실패)
        s.service.replaceItems(s.tenant, WorkflowSetup.AGENT, newYear, four());
        s.service.replaceItems(s.tenant, WorkflowSetup.AGENT, later, four());
        assertThat(fieldRequired(newYear)).contains("COMMISSION_GRADE@INS-A:PRD-1001").doesNotContain("TEST_ONLY_FIELD");
        assertThat(fieldRequired(later)).contains("COMMISSION_GRADE@INS-A:PRD-1001", "TEST_ONLY_FIELD@INS-A:PRD-1001");
    }

    private String fieldRequired(DisclosureId id) {
        return s.service.validate(s.tenant, WorkflowSetup.AGENT, id, ValidationStage.SEAL).stream()
                .filter(r -> r.ruleId().equals("R-FIELD-REQUIRED")).findFirst().orElseThrow().message();
    }
}
