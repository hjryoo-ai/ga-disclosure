package com.ga.disclosure.infra;

import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.rules.bundle.BundleLoader;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.disclosure.seal.renderer.DisclosurePdfRenderer;
import com.ga.disclosure.workflow.disclosure.ItemInput;
import com.ga.disclosure.workflow.disclosure.SealService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 봉인 조건 6종을 일으키는 상황(3B S6·S7·S11 공용). 데이터로만 만든다 — 소급 배포·시계·DB 상태.
 * <ul>
 *   <li>① 상담일(2026-09-23)에 걸치는 사규를 나중에 승인·활성화 → 재해석 결과의 사규 ≠ 고정(없음).</li>
 *   <li>② 상담일에 걸치는 서식 v2(2026-09-01부터, v1을 닫는다)를 나중에 배포 → 재해석 서식 ≠ 고정 v1.</li>
 *   <li>③ 스냅샷 산출 뒤 {@code snapshotMaxAgeDays}(7일)를 넘긴 시계.</li>
 *   <li>④ 추천 항목의 추천사유 행을 지운 REASONED(가변 상태라 DB가 허용) → SEAL 단계 오버라이드 불가 실패(R-REASON 등).</li>
 *   <li>⑤ 임시등록 항목(R-TEMP-PRODUCT, 오버라이드 가능)에 승인이 없다.</li>
 *   <li>⑥ 확인서가 가리키는 고객 참조가 없다(성명을 풀 수 없다).</li>
 * </ul>
 */
final class SealScenarios {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SealScenarios() {
    }

    /** ① 상담일에 걸치는 사규 HOUSE-2026(2026-07-01부터)을 승인·활성화. */
    static void retroactiveTenantRule(WorkflowSetup w) {
        w.in(() -> {
            w.rules.insert(new RuleVersion(RuleVersionId.of("HOUSE-2026"), RuleScope.TENANT, LocalDate.parse("2026-07-01"), null,
                    RuleStatus.DRAFT, null, null, JSON.readTree("{\"signDeadlineDays\": 10}"), null, null));
            return null;
        });
        new Governance().approval.approve(w.tenant, RuleVersionId.of("HOUSE-2026"), Governance.OPERATOR);
        new Governance("2026-09-24T00:00:00Z").activation.run(w.tenant, Governance.OPERATOR);
    }

    /** ② 서식 v2를 2026-09-01부터(상담일 이전) 배포 — v1은 그날로 닫힌다. */
    static void retroactiveTemplate(WorkflowSetup w) {
        String text = BundleFiles.fixtureText("templates/STANDARD-v2.bundle.json").replace("\"applyFrom\": \"2027-01-01\"", "\"applyFrom\": \"2026-09-01\"");
        new Governance().distribution.distribute(BundleLoader.parse("STANDARD-v2 (2026-09-01)", text), w.tenant, Governance.OPERATOR);
    }

    /**
     * 소급 GLOBAL 룰 DISC-2026-09(2026-09-01부터, DISC-2026-07을 대체, 최소 비교 개수만 {@code minCompare}로 바꾼 본문)를 배포·활성화한다.
     * DISC-2027-01이 배포되지 않은 테넌트에서만(그 번들이 DISC-2026-07을 이미 2027-01-01로 닫았으면 소급 대체는 거부된다).
     */
    static void retroactiveGlobalRule(WorkflowSetup w, int minCompare) {
        tools.jackson.databind.node.ObjectNode tree = (tools.jackson.databind.node.ObjectNode) JSON.readTree(
                com.ga.disclosure.rules.testing.Bundles.text(com.ga.disclosure.rules.testing.Bundles.DISC_2026_07));
        ((tools.jackson.databind.node.ObjectNode) tree.get("body")).put("minCompare", minCompare);
        String hash = com.ga.platform.canonical.Sha256.ofCanonical(JSON.writeValueAsString(tree.get("body")));
        tree.put("ruleVersionId", "DISC-2026-09").put("bundleId", "DISC-2026-09@" + hash.substring(0, 12)).put("applyFrom", "2026-09-01");
        tree.putObject("supersedes").put("ruleVersionId", "DISC-2026-07");
        new Governance().distribution.distribute(BundleLoader.parse("DISC-2026-09 (retroactive)", JSON.writeValueAsString(tree)), w.tenant,
                Governance.OPERATOR);
        new Governance("2026-09-24T00:00:00Z").activation.run(w.tenant, Governance.OPERATOR);
    }

    /** 2026 룰과 서식만 배포한 조립(소급 GLOBAL 대체 시나리오용). */
    static WorkflowSetup without2027Rule() {
        return new WorkflowSetup("2026-09-23T01:00:00Z", new com.ga.disclosure.infra.engine.EngineClientSettings(Duration.ofSeconds(2),
                Duration.ofSeconds(3), 3), List.of(com.ga.disclosure.rules.testing.Bundles.DISC_2026_07,
                com.ga.disclosure.rules.testing.Bundles.STANDARD_V1));
    }

    /** ③ 산출 8일 뒤의 시계로 봉인하는 서비스. */
    static SealService staleSeal(SealSetup s) {
        Clock later = Clock.offset(s.w.clock, Duration.ofDays(8));
        return new SealService(s.w.deps(later), s.ledger, s.cipher, s.records, s.bucket, new DisclosurePdfRenderer());
    }

    /** ④ 항목 1(추천)의 추천사유 행을 지운다(REASONED는 가변 상태 — V3가 허용). */
    static void dropRecommendation(WorkflowSetup w, DisclosureId id) {
        w.db.seed(w.tenant.value(), c -> SeedData.exec(c, "DELETE FROM recommendation WHERE tenant_id = ? AND disclosure_id = ? AND item_no = 1",
                w.tenant.value(), id.value()));
    }

    /** ⑤ 임시등록 항목(승인 없음)을 넣어 REASONED까지. */
    static DisclosureId reasonedWithTempProduct(WorkflowSetup w, String quoteDocNo) {
        DisclosureId id = w.draft();
        JsonNodeFactory f = JsonNodeFactory.instance;
        Map<String, JsonNode> values = Map.of("PREMIUM", f.numberNode(30100), "SURRENDER_VALUE_EXAMPLE", f.stringNode("가입설계서 참조"));
        w.service.replaceItems(Callers.of(w.tenant, WorkflowSetup.AGENT), id, List.of(WorkflowSetup.catalogItem("INS-A:PRD-1001", true),
                new ItemInput.Temp(InsurerCode.of("INS-D"), "(가상) 임시등록 상품", quoteDocNo, true, false, values),
                WorkflowSetup.catalogItem("INS-C:PRD-3120", false)));
        w.service.compare(Callers.of(w.tenant, WorkflowSetup.AGENT), id);
        w.service.requestGrades(Callers.of(w.tenant, WorkflowSetup.AGENT), id);
        w.service.setRecommendations(Callers.of(w.tenant, WorkflowSetup.AGENT), id, List.of(new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null),
                new AgentReason(2, List.of(ReasonCode.of("COVERAGE")), null)));
        return id;
    }

    /** ⑥ 확인서의 고객 참조를 존재하지 않는 참조로 바꾼다(가변 상태 본문 — V3가 허용). */
    static void orphanCustomer(WorkflowSetup w, DisclosureId id) {
        w.db.seed(w.tenant.value(), c -> SeedData.exec(c, "UPDATE disclosure SET customer_ref = ? WHERE tenant_id = ? AND disclosure_id = ?",
                "CR-" + "f".repeat(32), w.tenant.value(), id.value()));
    }
}
