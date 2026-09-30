package com.ga.disclosure.infra;

import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.disclosure.GradeSource;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.workflow.disclosure.CommandResult;
import com.ga.disclosure.workflow.disclosure.DisclosureItem;
import com.ga.disclosure.workflow.disclosure.DisclosureRecord;
import com.ga.disclosure.workflow.disclosure.ItemInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3A 흐름(설계서 부록 A-1·A-2): 초안 → 항목 → 비교 → 산출(실제 HTTP 엔진 클라이언트 + 계약 검증 페이크) → 사유 → REASONED가 DB에 V6 형태로
 * 저장되고 다시 읽어도 같다. 임시등록 1건 포함 흐름과 고객 요청 보험사 추가 → COMPARED 회귀 → 재산출 흐름.
 */
class DisclosureFlowIT {

    private final WorkflowSetup s = new WorkflowSetup();

    @AfterEach
    void stop() {
        s.close();
    }

    private DisclosureRecord load(DisclosureId id) {
        return s.in(() -> s.disclosures.loadForUpdate(id).orElseThrow());
    }

    @Test
    void draftToReasonedIsStoredAndReloaded() {
        DisclosureId id = s.compared();
        CommandResult graded = s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id);
        assertThat(graded.applied()).as("%s %s", graded.rejectionOrNull(), graded.engineViolations()).isTrue();
        assertThat(graded.status()).isEqualTo(DisclosureStatus.GRADED);
        CommandResult reasoned = s.service.setRecommendations(s.tenant, WorkflowSetup.AGENT, id, List.of(
                new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null),
                new AgentReason(3, List.of(ReasonCode.of("COVERAGE")), null)));
        assertThat(reasoned.applied()).as("%s", reasoned.results()).isTrue();

        DisclosureRecord r = load(id);
        assertThat(r.status()).isEqualTo(DisclosureStatus.REASONED);
        assertThat(r.snapshotOrNull().snapshot().gradingPolicyVersionId()).isEqualTo("GRADING-2026-07");
        assertThat(r.snapshotOrNull().basisCanonicalJson()).contains("ASSOC_DISCLOSURE");
        assertThat(r.items()).extracting(i -> ((ItemGrade.Ok) i.gradeOrNull()).rankInSet()).containsExactly(1, 3, 2);
        assertThat(r.items().get(0).recommendation().orElseThrow().codes()).containsExactly(ReasonCode.of("PREMIUM"));
        assertThat(r.items().get(1).recommendation()).isEmpty();
        assertThat(r.items().get(0).draft().fieldValues()).containsKeys("PREMIUM", "SURRENDER_VALUE_EXAMPLE", "INSURER_NAME", "PRODUCT_NAME");
        assertThat(s.engine.requestViolations()).isEmpty();
        assertThat(s.engine.selfCheckFailures()).isEmpty();
    }

    @Test
    void tempProductIsGradedLocallyAndNeverSentToTheEngine() {
        DisclosureId id = s.draft();
        s.service.replaceItems(s.tenant, WorkflowSetup.AGENT, id, List.of(WorkflowSetup.catalogItem("INS-A:PRD-1001", true),
                new ItemInput.Temp(InsurerCode.of("INS-D"), "(가상) 임시등록 상품", "Q-2026-0001", true, false, Map.of(
                        "INSURER_NAME", tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("(가상) INS-D"),
                        "PRODUCT_NAME", tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("(가상) 임시등록 상품"),
                        "PREMIUM", tools.jackson.databind.node.JsonNodeFactory.instance.numberNode(30000),
                        "SURRENDER_VALUE_EXAMPLE", tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("가입설계서 참조"))),
                WorkflowSetup.catalogItem("INS-C:PRD-3120", false)));
        assertThat(s.service.compare(s.tenant, WorkflowSetup.AGENT, id).applied()).isTrue();
        CommandResult graded = s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id);
        assertThat(graded.applied()).as("%s", graded.engineViolations()).isTrue();
        assertThat(s.engine.requestViolations()).as("임시등록은 요청에 없다(계약 요청 스키마 통과)").isEmpty();

        DisclosureItem temp = load(id).items().get(1);
        assertThat(temp.draft().productKey()).isEmpty();
        assertThat(temp.gradeOrNull()).isEqualTo(ItemGrade.Unavailable.localTempProduct());
        assertThat(temp.gradeOrNull().source()).isEqualTo(GradeSource.LOCAL);
        assertThat(load(id).snapshotOrNull().snapshot().items()).hasSize(2);
        List<String> row = s.db.asApp(s.tenant.value(), c -> {
            try (var ps = c.prepareStatement("""
                    SELECT product_key IS NULL, quote_doc_no, grade_status, grade_source, unavailable_reason, rank_in_set IS NULL
                      FROM disclosure_item WHERE tenant_id = ? AND disclosure_id = ? AND item_no = 2""")) {
                ps.setString(1, s.tenant.value());
                ps.setObject(2, id.value());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));
                }
            }
        });
        assertThat(row).containsExactly("t", "Q-2026-0001", "UNAVAILABLE", "LOCAL", "TEMP_PRODUCT", "t");
    }

    @Test
    void customerRequestedInsurerSendsItBackToComparedAndRegrades() {
        DisclosureId id = s.compared();
        s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id);
        List<ItemInput> four = List.of(WorkflowSetup.catalogItem("INS-A:PRD-1001", true), WorkflowSetup.catalogItem("INS-B:PRD-2044", false),
                WorkflowSetup.catalogItem("INS-C:PRD-3120", true),
                new ItemInput.Catalog(com.ga.disclosure.domain.vo.ProductKey.parse("INS-E:PRD-5001"), false, true, Map.of()));
        CommandResult replaced = s.service.replaceItems(s.tenant, WorkflowSetup.AGENT, id, four);
        assertThat(replaced.status()).isEqualTo(DisclosureStatus.COMPARED);
        assertThat(load(id).snapshotOrNull()).isNull();

        CommandResult regraded = s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id);
        assertThat(regraded.applied()).isTrue();
        // E는 A와 같은 rankKey → SHARED_RANK 동순위 1, 1, 3, 4
        assertThat(load(id).items()).extracting(i -> ((ItemGrade.Ok) i.gradeOrNull()).rankInSet()).containsExactly(1, 4, 3, 1);
        CommandResult reasoned = s.service.setRecommendations(s.tenant, WorkflowSetup.AGENT, id, List.of(
                new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null), new AgentReason(3, List.of(ReasonCode.of("COVERAGE")), null)));
        assertThat(reasoned.applied()).as("%s", reasoned.results()).isTrue();
        assertThat(load(id).items().get(3).recommendation().orElseThrow().codes()).as("고객 요청 항목에는 룰의 자동 부가 코드")
                .containsExactlyElementsOf(s.in(() -> new com.ga.disclosure.rules.resolve.RuleResolver(s.rules)
                        .resolve(s.tenant, WorkflowSetup.CONSULT).autoReasonCodes()));
    }
}
