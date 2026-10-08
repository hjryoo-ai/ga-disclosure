package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.disclosure.SealService.Rejection;
import com.ga.disclosure.workflow.disclosure.SealService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B S6: 봉인 조건 6종 각각 단독 실패, 그리고 6종 동시 실패. 매번 업무 거부(커밋)이며 상태·번호·카운터·체인 머리·{@code document_key}·
 * {@code document_artifact}·저장소 객체가 그대로이고, 거부 목록에 실패한 조건이 전부(단락 없이) 있으며, 감사는 {@code DISCLOSURE_SEAL_REJECTED}
 * 1행뿐이다(성명 열람 기록 없음). ①·②는 {@code RULE_SUPERSEDED_DRAFT} 플래그를 남긴다.
 */
class SealRejectionIT {

    private final SealSetup s = new SealSetup();

    @AfterEach
    void close() {
        s.close();
    }

    private record Snapshot(String status, long sealed, long counters, long heads, long keys, long artifacts, int objects, int audit) {
    }

    private Snapshot state(DisclosureId id) {
        String t = s.w.tenant.value();
        return new Snapshot(s.text("SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", t, id.value()),
                s.count("SELECT count(*) FROM disclosure WHERE tenant_id = ? AND disclosure_no IS NOT NULL", t),
                s.count("SELECT count(*) FROM disclosure_counter WHERE tenant_id = ?", t),
                s.count("SELECT count(*) FROM disclosure_chain_head WHERE tenant_id = ?", t),
                s.count("SELECT count(*) FROM document_key WHERE tenant_id = ?", t),
                s.count("SELECT count(*) FROM document_artifact WHERE tenant_id = ?", t),
                s.objects(), s.audit().size());
    }

    /** 거부를 확인하고 새로 생긴 감사 행들을 돌려준다. */
    private List<AuditRecord> rejected(DisclosureId id, Function<DisclosureId, SealService.Outcome> seal, Rejection... expected) {
        Snapshot before = state(id);
        SealService.Outcome o = seal.apply(id);
        assertThat(o.sealed()).isFalse();
        assertThat(o.rejections()).containsExactly(expected);
        assertThat(o.number()).isEmpty();
        Snapshot after = state(id);
        assertThat(after.status()).isEqualTo(DisclosureStatus.REASONED.name()).isEqualTo(before.status());
        assertThat(after).as("상태·번호·카운터·체인·키·산출물·저장소 불변, 감사 +1")
                .isEqualTo(new Snapshot(before.status(), before.sealed(), before.counters(), before.heads(), before.keys(), before.artifacts(),
                        before.objects(), before.audit() + 1));
        List<AuditRecord> all = s.audit();
        List<AuditRecord> added = all.subList(before.audit(), all.size());
        assertThat(added).extracting(r -> r.entry().action()).containsExactly(AuditAction.DISCLOSURE_SEAL_REJECTED);
        assertThat(added.getFirst().entry().detail().path("rejections")).extracting(n -> n.asString())
                .containsExactly(java.util.Arrays.stream(expected).map(Enum::name).toArray(String[]::new));
        return added;
    }

    private SealService.Outcome sealNow(DisclosureId id) {
        return s.seal.seal(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id);
    }

    @Test
    void ruleSuperseded() {
        DisclosureId id = s.w.reasoned();
        SealScenarios.retroactiveTenantRule(s.w);
        List<AuditRecord> added = rejected(id, this::sealNow, Rejection.RULE_SUPERSEDED);
        assertThat(added.getFirst().entry().detail().path("flag").path("type").asString()).isEqualTo("RULE_SUPERSEDED_DRAFT");
        assertThat(added.getFirst().entry().detail().path("currentTenantRuleVersionId").asString()).isEqualTo("HOUSE-2026");
        assertThat(s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ? AND disclosure_id = ? AND type = 'RULE_SUPERSEDED_DRAFT'"
                + " AND resolved_at IS NULL", s.w.tenant.value(), id.value())).isEqualTo(1);
    }

    @Test
    void templateSuperseded() {
        DisclosureId id = s.w.reasoned();
        SealScenarios.retroactiveTemplate(s.w);
        rejected(id, this::sealNow, Rejection.TEMPLATE_SUPERSEDED);
        // 두 번 거부해도 열린 플래그는 하나(재사용)
        rejected(id, this::sealNow, Rejection.TEMPLATE_SUPERSEDED);
        assertThat(s.count("SELECT count(*) FROM compliance_flag WHERE tenant_id = ? AND disclosure_id = ? AND type = 'RULE_SUPERSEDED_DRAFT'",
                s.w.tenant.value(), id.value())).isEqualTo(1);
    }

    @Test
    void snapshotStale() {
        DisclosureId id = s.w.reasoned();
        SealService stale = SealScenarios.staleSeal(s);
        rejected(id, x -> stale.seal(Callers.of(s.w.tenant, WorkflowSetup.AGENT), x), Rejection.SNAPSHOT_STALE);
    }

    @Test
    void validationBlocked() {
        DisclosureId id = s.w.reasoned();
        SealScenarios.dropRecommendation(s.w, id);
        List<AuditRecord> added = rejected(id, this::sealNow, Rejection.VALIDATION_BLOCKED);
        assertThat(added.getFirst().entry().detail().path("results").findValuesAsString("ruleId")).contains("R-REASON");
    }

    @Test
    void approvalMissing() {
        DisclosureId id = SealScenarios.reasonedWithTempProduct(s.w, "Q-2026-0001");
        rejected(id, this::sealNow, Rejection.APPROVAL_MISSING);
    }

    @Test
    void customerNameUnavailable() {
        DisclosureId id = s.w.reasoned();
        SealScenarios.orphanCustomer(s.w, id);
        rejected(id, this::sealNow, Rejection.CUSTOMER_NAME_UNAVAILABLE);
        assertThat(s.audit()).noneMatch(r -> r.entry().action() == AuditAction.CUSTOMER_VIEW);
    }

    /** 6종 동시: 단락 없이 전부, 평가 순서대로. */
    @Test
    void allSixAtOnceAreReportedTogether() {
        DisclosureId id = SealScenarios.reasonedWithTempProduct(s.w, "Q-2026-0002");
        SealScenarios.dropRecommendation(s.w, id);
        SealScenarios.orphanCustomer(s.w, id);
        SealScenarios.retroactiveTenantRule(s.w);
        SealScenarios.retroactiveTemplate(s.w);
        SealService stale = SealScenarios.staleSeal(s);
        rejected(id, x -> stale.seal(Callers.of(s.w.tenant, WorkflowSetup.AGENT), x), Rejection.RULE_SUPERSEDED, Rejection.TEMPLATE_SUPERSEDED,
                Rejection.SNAPSHOT_STALE, Rejection.VALIDATION_BLOCKED, Rejection.APPROVAL_MISSING, Rejection.CUSTOMER_NAME_UNAVAILABLE);
    }
}
