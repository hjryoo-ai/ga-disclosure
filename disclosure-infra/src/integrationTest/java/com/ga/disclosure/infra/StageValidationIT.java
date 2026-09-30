package com.ga.disclosure.infra;

import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.workflow.disclosure.CommandRejectedException;
import com.ga.disclosure.workflow.disclosure.CommandResult;
import com.ga.disclosure.workflow.disclosure.ItemInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 3A W5(승인 조정본): 각 단계 검증은 룰의 {@code stages}대로만 실행되고 오버라이드 불가 실패는 전이를 막는다. 오버라이드 가능 실패는 중간
 * 단계에서 전이를 막지 않고 플래그·감사만 남긴다. SEAL 판정 함수는 승인({@code review}, 대상 해시 일치) 없는 오버라이드 가능 실패를
 * 통과시키지 않고, 대상이 바뀌면 승인이 효력을 잃으며, 같은 대상이면 재산출 뒤에도 유지된다.
 */
class StageValidationIT {

    private final WorkflowSetup s = new WorkflowSetup();

    @AfterEach
    void stop() {
        s.close();
    }

    private EffectiveRule rule() {
        return s.in(() -> new RuleResolver(s.rules).resolve(s.tenant, WorkflowSetup.CONSULT));
    }

    private List<String> validatedIds(DisclosureId id, ValidationStage stage) {
        List<String> ids = new ArrayList<>();
        s.auditLog().stream().filter(a -> a.entry().action().name().equals("DISCLOSURE_VALIDATE"))
                .filter(a -> id.toString().equals(a.entry().targetId()))
                .filter(a -> a.entry().detail().get("stage").asString().equals(stage.name()))
                .reduce((a, b) -> b)
                .ifPresent(a -> a.entry().detail().get("results").forEach(r -> ids.add(r.get("ruleId").asString())));
        return ids;
    }

    private static Map<String, JsonNode> tempValues(String name) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        return Map.of("INSURER_NAME", f.stringNode("(가상) INS-D"), "PRODUCT_NAME", f.stringNode(name), "PREMIUM", f.numberNode(30000),
                "SURRENDER_VALUE_EXAMPLE", f.stringNode("가입설계서 참조"));
    }

    private static List<ItemInput> withTemp(String quote) {
        return List.of(WorkflowSetup.catalogItem("INS-A:PRD-1001", true),
                new ItemInput.Temp(InsurerCode.of("INS-D"), "(가상) 임시 " + quote, quote, true, false, tempValues("(가상) 임시 " + quote)),
                WorkflowSetup.catalogItem("INS-C:PRD-3120", false));
    }

    private static List<AgentReason> reasons() {
        return List.of(new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null), new AgentReason(2, List.of(ReasonCode.of("COVERAGE")), null));
    }

    @Test
    void eachStageRunsExactlyTheRulesListedForItInRuleData() {
        DisclosureId id = s.compared();
        s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id);
        s.service.setRecommendations(s.tenant, WorkflowSetup.AGENT, id, List.of(new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null),
                new AgentReason(3, List.of(ReasonCode.of("COVERAGE")), null)));
        EffectiveRule rule = rule();
        for (ValidationStage stage : List.of(ValidationStage.COMPARE, ValidationStage.GRADE, ValidationStage.REASON)) {
            assertThat(validatedIds(id, stage)).as(stage.name()).isNotEmpty().containsExactlyElementsOf(rule.validationsFor(stage));
        }
    }

    @Test
    void blockingFailureStopsTheTransitionAndIsAuditedAsARejection() {
        DisclosureId id = s.draft();
        s.service.replaceItems(s.tenant, WorkflowSetup.AGENT, id, WorkflowSetup.threeItems().subList(0, 2));
        CommandResult r = s.service.compare(s.tenant, WorkflowSetup.AGENT, id);
        assertThat(r.rejectionOrNull()).isEqualTo(CommandResult.Rejection.VALIDATION_BLOCKED);
        assertThat(r.status()).isEqualTo(DisclosureStatus.DRAFT);
        assertThat(s.auditLog().getLast().entry().action().name()).isEqualTo("DISCLOSURE_REJECT");
        assertThat(s.auditLog().getLast().entry().detail().get("blocking").toString()).contains("R-MIN-COMPARE");
    }

    @Test
    void overridableFailuresPassMidStagesWithAFlagButSealNeedsMatchingApprovals() {
        DisclosureId id = s.draft();
        s.service.replaceItems(s.tenant, WorkflowSetup.AGENT, id, withTemp("Q-2026-0001"));
        CommandResult compared = s.service.compare(s.tenant, WorkflowSetup.AGENT, id);
        assertThat(compared.applied()).isTrue();
        assertThat(compared.results()).filteredOn(ValidationResult::overridable).extracting(ValidationResult::ruleId).containsExactly("R-TEMP-PRODUCT");
        assertThat(openFlags(id)).containsExactly("VALIDATION_OVERRIDE:" + id + "/R-TEMP-PRODUCT");

        assertThat(s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id).applied()).isTrue();
        assertThat(s.service.setRecommendations(s.tenant, WorkflowSetup.AGENT, id, reasons()).status()).isEqualTo(DisclosureStatus.REASONED);
        assertThat(openFlags(id)).containsExactlyInAnyOrder("VALIDATION_OVERRIDE:" + id + "/R-TEMP-PRODUCT",
                "VALIDATION_OVERRIDE:" + id + "/R-GRADE-UNAVAILABLE");

        // 봉인 판정: 승인 없는 오버라이드 가능 실패 2건이 막는다
        List<ValidationResult> blockers = s.service.sealBlockers(s.tenant, WorkflowSetup.MANAGER, id);
        assertThat(blockers).extracting(ValidationResult::ruleId).containsExactlyInAnyOrder("R-TEMP-PRODUCT", "R-GRADE-UNAVAILABLE");

        // 틀린 대상 해시로는 승인할 수 없다
        CommandRejectedException wrong = catchThrowableOfType(CommandRejectedException.class, () -> s.service.approveException(s.tenant,
                WorkflowSetup.MANAGER, id, "R-TEMP-PRODUCT", "0".repeat(64), "현장 확인"));
        assertThat(wrong.code()).isEqualTo("APPROVAL_SUBJECT_MISMATCH");

        for (ValidationResult b : blockers) {
            s.service.approveException(s.tenant, WorkflowSetup.MANAGER, id, b.ruleId(), b.subjectHash().orElseThrow(), "관리자 확인 완료");
        }
        assertThat(s.service.sealBlockers(s.tenant, WorkflowSetup.MANAGER, id)).isEmpty();

        // 같은 대상이면 재산출 뒤에도 승인 유지(REASONED → GRADED → REASONED)
        assertThat(s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id).applied()).isTrue();
        s.service.setRecommendations(s.tenant, WorkflowSetup.AGENT, id, reasons());
        assertThat(s.service.sealBlockers(s.tenant, WorkflowSetup.MANAGER, id)).isEmpty();

        // 대상이 바뀌면(다른 발행번호의 임시등록) 승인은 효력이 없다
        s.service.replaceItems(s.tenant, WorkflowSetup.AGENT, id, withTemp("Q-2026-0002"));
        s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id);
        s.service.setRecommendations(s.tenant, WorkflowSetup.AGENT, id, reasons());
        assertThat(s.service.sealBlockers(s.tenant, WorkflowSetup.MANAGER, id)).extracting(ValidationResult::ruleId)
                .containsExactlyInAnyOrder("R-TEMP-PRODUCT", "R-GRADE-UNAVAILABLE");
    }

    @Test
    void approvalsCannotBeRecordedAfterSealingOrRewritten() {
        String t = s.tenant.value();
        UUID[] sealed = new UUID[1];
        s.db.seed(t, c -> sealed[0] = SeedData.disclosure(c, t, "SEALED", SeedData.hash('a')));
        assertRejected(s.db, t, "GD080", """
                INSERT INTO review (tenant_id, review_id, disclosure_id, rule_id, subject_hash, approved_by, approved_role, approved_at, reason)
                VALUES (?, gen_random_uuid(), ?, 'R-TEMP-PRODUCT', repeat('0', 64), 'm', 'MANAGER', now(), '사유')""", t, sealed[0]);
        UUID[] draft = new UUID[1];
        s.db.seed(t, c -> {
            draft[0] = SeedData.disclosure(c, t, "DRAFT", null);
            SeedData.review(c, t, draft[0], "R-TEMP-PRODUCT");
        });
        // 앱 롤은 권한부터 없다(SELECT·INSERT만), 소유자 롤도 append-only 트리거가 막는다
        assertRejected(s.db, t, "42501", "UPDATE review SET reason = '바꿈' WHERE tenant_id = ? AND disclosure_id = ?", t, draft[0]);
        assertRejected(s.db, t, "42501", "DELETE FROM review WHERE tenant_id = ? AND disclosure_id = ?", t, draft[0]);
        assertThat(TriggerAssertions.sqlStateOf(() -> s.db.seed(t, c -> SeedData.exec(c,
                "UPDATE review SET reason = '바꿈' WHERE tenant_id = ? AND disclosure_id = ?", t, draft[0])))).isEqualTo("GD030");
        assertThat(TriggerAssertions.sqlStateOf(() -> s.db.seed(t, c -> SeedData.exec(c,
                "DELETE FROM review WHERE tenant_id = ? AND disclosure_id = ?", t, draft[0])))).isEqualTo("GD030");
    }

    private List<String> openFlags(DisclosureId id) {
        return s.db.asApp(s.tenant.value(), c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("""
                    SELECT type || ':' || target_id FROM compliance_flag
                     WHERE tenant_id = ? AND disclosure_id = ? AND resolved_at IS NULL ORDER BY target_id""")) {
                ps.setString(1, s.tenant.value());
                ps.setObject(2, id.value());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }
}
