package com.ga.disclosure.infra;

import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.ReasonCode;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3A W6: 룰·서식 버전은 초안 생성 때 상담일로 한 번 해석해 고정되고, 이후 룰 데이터가 바뀌어도(사규 신설, 상담일에 걸치는 서식 새 버전
 * 배포) 그 확인서에는 영향이 없다. 경계일 2026-12-31(DISC-2026-07, 최소 3)·2027-01-01(DISC-2027-01, 최소 4) 두 초안으로 확인한다.
 * 3B S13: 서식 v2의 TEST_ONLY_FIELD(AGENT_INPUT 결속)는 추천사유로 충족되지 않고 설계사 입력으로만 충족된다.
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
        return s.service.createDraft(Callers.of(s.tenant, WorkflowSetup.AGENT), s.customer, WorkflowSetup.GROUP, consult, TemplateType.STANDARD);
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

        // 기존 초안의 판정은 고정한 룰로 낸다: 검증 감사의 룰 정체 = 초안 생성 때 고정한 것(사규 없음, 같은 본문 해시)
        String createdHash = s.auditLog().stream().filter(a -> a.entry().action().name().equals("DISCLOSURE_CREATE"))
                .filter(a -> eve.toString().equals(a.entry().targetId())).findFirst().orElseThrow().entry().detail().get("ruleBodyHash").asString();
        s.service.validate(Callers.of(s.tenant, WorkflowSetup.AGENT), eve, ValidationStage.COMPARE);
        var validated = s.auditLog().getLast().entry().detail();
        assertThat(validated.get("ruleVersionId").asString()).isEqualTo("DISC-2026-07");
        assertThat(validated.get("tenantRuleVersionId").isNull()).as("뒤에 생긴 사규는 기존 초안의 판정에 쓰이지 않는다").isTrue();
        assertThat(validated.get("ruleBodyHash").asString()).isEqualTo(createdHash);
        assertThat(validated.get("templateVersion").asInt()).isEqualTo(1);

        // 새 초안은 새 데이터를, 기존 초안은 고정 버전을 쓴다
        DisclosureId later = draftOn(NEW_YEAR);
        assertThat(pinned(later)).isEqualTo("DISC-2027-01|HOUSE-2026|STANDARD v2");
        assertThat(pinned(eve)).isEqualTo("DISC-2026-07|-|STANDARD v1");
        assertThat(pinned(newYear)).isEqualTo("DISC-2027-01|-|STANDARD v1");

        // 경계: 3개 항목은 2026-12-31 초안(최소 3)만 통과, 2027-01-01 초안(최소 4)은 거부
        s.service.replaceItems(Callers.of(s.tenant, WorkflowSetup.AGENT), eve, WorkflowSetup.threeItems());
        s.service.replaceItems(Callers.of(s.tenant, WorkflowSetup.AGENT), newYear, WorkflowSetup.threeItems());
        assertThat(s.service.compare(Callers.of(s.tenant, WorkflowSetup.AGENT), eve).status()).isEqualTo(DisclosureStatus.COMPARED);
        CommandResult blocked = s.service.compare(Callers.of(s.tenant, WorkflowSetup.AGENT), newYear);
        assertThat(blocked.rejectionOrNull()).isEqualTo(CommandResult.Rejection.VALIDATION_BLOCKED);
        assertThat(blocked.results()).filteredOn(ValidationResult::blocking).extracting(ValidationResult::ruleId).containsExactly("R-MIN-COMPARE");

        // 서식: 고정된 v1과 새 초안의 v2(필수 항목 TEST_ONLY_FIELD 추가)는 같은 항목에서 다른 필수 목록으로 검증된다(산출 전이라 둘 다 실패)
        s.service.replaceItems(Callers.of(s.tenant, WorkflowSetup.AGENT), newYear, four());
        s.service.replaceItems(Callers.of(s.tenant, WorkflowSetup.AGENT), later, four());
        assertThat(fieldRequired(newYear)).contains("COMMISSION_GRADE@INS-A:PRD-1001").doesNotContain("TEST_ONLY_FIELD");
        assertThat(fieldRequired(later)).contains("COMMISSION_GRADE@INS-A:PRD-1001", "TEST_ONLY_FIELD@INS-A:PRD-1001");

        // 3B S13: TEST_ONLY_FIELD는 AGENT_INPUT 결속 — 추천 항목의 추천사유가 모두 있어도 그것으로 충족되지 않는다(3A D2의 과대 충족 폐기).
        assertThat(s.service.compare(Callers.of(s.tenant, WorkflowSetup.AGENT), later).applied()).isTrue();
        assertThat(s.service.requestGrades(Callers.of(s.tenant, WorkflowSetup.AGENT), later).applied()).isTrue();
        List<AgentReason> reasons = List.of(new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null),
                new AgentReason(3, List.of(ReasonCode.of("COVERAGE")), null));
        CommandResult reasonsOnly = s.service.setRecommendations(Callers.of(s.tenant, WorkflowSetup.AGENT), later, reasons);
        assertThat(reasonsOnly.rejectionOrNull()).isEqualTo(CommandResult.Rejection.VALIDATION_BLOCKED);
        String missing = reasonsOnly.results().stream().filter(r -> r.ruleId().equals("R-FIELD-REQUIRED")).findFirst().orElseThrow().message();
        assertThat(missing).contains("TEST_ONLY_FIELD@INS-A:PRD-1001", "TEST_ONLY_FIELD@INS-C:PRD-3120")   // 추천사유가 있는 두 항목
                .doesNotContain("RECOMMENDATION_REASON");
        // 설계사가 그 항목에 값을 입력하면 충족된다(입력은 항목 교체 → 재비교·재산출)
        List<ItemInput> withAgentInput = four().stream().<ItemInput>map(i -> {
            ItemInput.Catalog c = (ItemInput.Catalog) i;
            return new ItemInput.Catalog(c.productKey(), c.recommended(), c.requestedByCustomer(),
                    Map.of("TEST_ONLY_FIELD", JSON.getNodeFactory().stringNode("설계사 입력")));
        }).toList();
        s.service.replaceItems(Callers.of(s.tenant, WorkflowSetup.AGENT), later, withAgentInput);
        s.service.requestGrades(Callers.of(s.tenant, WorkflowSetup.AGENT), later);
        assertThat(s.service.setRecommendations(Callers.of(s.tenant, WorkflowSetup.AGENT), later, reasons).status()).isEqualTo(DisclosureStatus.REASONED);
        assertThat(fieldRequired(later)).doesNotContain("TEST_ONLY_FIELD");
    }

    private String fieldRequired(DisclosureId id) {
        return s.service.validate(Callers.of(s.tenant, WorkflowSetup.AGENT), id, ValidationStage.SEAL).stream()
                .filter(r -> r.ruleId().equals("R-FIELD-REQUIRED")).findFirst().orElseThrow().message();
    }
}
