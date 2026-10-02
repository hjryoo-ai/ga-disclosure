package com.ga.disclosure.infra;

import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPayloads;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.outbox.EventContract;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.LifecycleReason;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 아웃박스(설계서 §4.5 v1.9, 4 계획 승인 Q13): 3A·3B 경로의 상태 변경이 같은 트랜잭션에서 계약 이벤트를 적재한다 — 테넌트 내 seq는 1부터 갭 없이,
 * 모든 envelope가 계약 스키마를 통과하고, 플래그는 새로 열릴 때만 한 번, 상태 변경이 롤백되면 이벤트도 없다. payload에 고객 개인정보·사유 텍스트가
 * 없다.
 */
class OutboxEventsIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SealSetup s = new SealSetup();

    @AfterEach
    void close() {
        s.close();
    }

    /** 테넌트 아웃박스를 seq 순으로 envelope 형태로 읽는다. */
    private List<ObjectNode> envelopes() {
        return s.w.db.asApp(s.w.tenant.value(), c -> {
            List<ObjectNode> out = new ArrayList<>();
            try (var ps = c.prepareStatement("""
                    SELECT seq, event_id, type, version, occurred_at, aggregate_kind, aggregate_id, payload::text
                      FROM outbox_event WHERE tenant_id = ? ORDER BY seq""")) {
                ps.setString(1, s.w.tenant.value());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode e = JSON.createObjectNode();
                        e.put("eventId", rs.getString("event_id"));
                        e.put("seq", rs.getLong("seq"));
                        e.put("type", rs.getString("type"));
                        e.put("version", rs.getInt("version"));
                        e.put("occurredAt", rs.getTimestamp("occurred_at").toInstant().toString());
                        e.putObject("aggregate").put("kind", rs.getString("aggregate_kind")).put("id", rs.getString("aggregate_id"));
                        e.set("payload", JSON.readTree(rs.getString(8)));
                        out.add(e);
                    }
                }
            }
            return out;
        });
    }

    private static List<String> types(List<ObjectNode> events) {
        return events.stream().map(e -> e.path("type").asString()).toList();
    }

    @Test
    void lifecycleEventsAreGaplessContractValidAndFreeOfPersonalData() {
        DisclosureId id = s.sealReasoned().id();
        LifecycleService.Outcome superseded = s.lifecycle.supersede(s.w.tenant, SealSetup.MANAGER, id, new LifecycleReason("CONTENT_ERROR", "(가상) 오기"));
        DisclosureId next = superseded.newVersion().orElseThrow();
        s.lifecycle.voidDisclosure(s.w.tenant, WorkflowSetup.AGENT, next, new LifecycleReason("OTHER", "(가상) 상담 철회 메모"));

        List<ObjectNode> events = envelopes();
        assertThat(types(events)).containsExactly("DisclosureCreated", "DisclosureSealed", "DisclosureSuperseded", "DisclosureCreated",
                "DisclosureVoided");
        for (int i = 0; i < events.size(); i++) {
            ObjectNode e = events.get(i);
            assertThat(e.path("seq").asLong()).as("gapless").isEqualTo(i + 1);
            assertThat(EventContract.validate(e)).as("contract: %s", e.path("type").asString()).isEmpty();
            String text = e.toString();
            assertThat(text).as("no customer name, no reason text").doesNotContain("가상고객").doesNotContain("오기").doesNotContain("상담 철회");
        }
        assertThat(events.get(0).at("/payload/agentId").asString()).isEqualTo(WorkflowSetup.AGENT_ID);
        assertThat(events.get(1).at("/payload/disclosureNo").asString()).startsWith(s.w.tenant.value() + "-2026-");
        assertThat(events.get(2).at("/payload/supersededById").asString()).isEqualTo(next.toString());
        assertThat(events.get(3).at("/payload/supersedesId").asString()).isEqualTo(id.toString());
        assertThat(events.get(3).at("/payload/version").asInt()).isEqualTo(2);
        assertThat(events.get(4).at("/payload/previousStatus").asString()).isEqualTo("DRAFT");
        assertThat(events.get(4).at("/payload/disclosureNo").isNull()).as("voided before sealing").isTrue();
    }

    @Test
    void flagsEmitOnlyWhenTheyOpen() {
        DisclosureId id = s.w.compared();
        long before = envelopes().size();
        s.w.in(() -> s.w.flags.raise(DisclosureFlagPort.Type.GRADE_INCONSISTENT, "HIGH", id, "DISCLOSURE", id.toString(), s.w.clock.instant()));
        s.w.in(() -> s.w.flags.raise(DisclosureFlagPort.Type.GRADE_INCONSISTENT, "HIGH", id, "DISCLOSURE", id.toString(), s.w.clock.instant()));
        List<ObjectNode> events = envelopes();
        assertThat(events).hasSize((int) before + 1);
        ObjectNode flag = events.getLast();
        assertThat(flag.path("type").asString()).isEqualTo("ComplianceFlagRaised");
        assertThat(flag.at("/aggregate/kind").asString()).isEqualTo("COMPLIANCE_FLAG");
        assertThat(flag.at("/payload/disclosureId").asString()).isEqualTo(id.toString());
        assertThat(flag.at("/payload/agentId").asString()).isEqualTo(WorkflowSetup.AGENT_ID);
        assertThat(EventContract.validate(flag)).isEmpty();
    }

    @Test
    void aRolledBackCommandLeavesNoEventAndTheNextSeqHasNoGap() {
        DisclosureId id = s.w.draft();
        int before = envelopes().size();
        assertThatThrownBy(() -> s.w.in(() -> {
            s.w.outbox.append(EventType.PolicyLinked, id.toString(), Instant.parse("2026-09-24T00:00:00Z"),
                    OutboxPayloads.policyLinked(id.value(), s.w.tenant.value() + "-2026-000001", "POL-1", java.time.LocalDate.parse("2026-09-24")));
            throw new IllegalStateException("injected after append");
        })).hasMessageContaining("injected");
        assertThat(envelopes()).hasSize(before);
        s.w.draft();
        List<ObjectNode> events = envelopes();
        assertThat(events.getLast().path("seq").asLong()).isEqualTo(before + 1);
    }

    @Test
    void payloadThatViolatesTheContractIsACommandError() {
        DisclosureId id = s.w.draft();
        JsonNode bad = OutboxPayloads.disclosureVoided(id.value(), null, "NOT_A_STATUS", Instant.parse("2026-09-24T00:00:00Z"));
        assertThatThrownBy(() -> s.w.in(() -> s.w.outbox.append(EventType.DisclosureVoided, id.toString(), Instant.parse("2026-09-24T00:00:00Z"),
                bad))).isInstanceOf(IllegalStateException.class).hasMessageContaining("contracts/events/v1");
        JsonNode badAgent = OutboxPayloads.disclosureCreated(UUID.randomUUID(), 1, "agent@with-at", "CR-1", "PG-HEALTH", java.time.LocalDate.parse("2026-09-23"),
                "STANDARD", 1, "SELF", null);
        assertThatThrownBy(() -> s.w.in(() -> s.w.outbox.append(EventType.DisclosureCreated, UUID.randomUUID().toString(),
                Instant.parse("2026-09-24T00:00:00Z"), badAgent))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createDraftNeedsAnAgentLinkedInIdentityLink() {
        int before = envelopes().size();
        assertThatThrownBy(() -> s.w.service.createDraft(s.w.tenant, new com.ga.disclosure.workflow.Actor("stranger@test", "AGENT"), s.w.customer,
                WorkflowSetup.GROUP, WorkflowSetup.CONSULT, com.ga.disclosure.domain.enums.TemplateType.STANDARD))
                .isInstanceOf(com.ga.disclosure.workflow.disclosure.CommandRejectedException.class)
                .hasMessageNotContaining("stranger");
        assertThatThrownBy(() -> s.w.service.createDraft(s.w.tenant, WorkflowSetup.MANAGER, s.w.customer, WorkflowSetup.GROUP, WorkflowSetup.CONSULT,
                com.ga.disclosure.domain.enums.TemplateType.STANDARD)).as("linked, but not as an agent")
                .isInstanceOf(com.ga.disclosure.workflow.disclosure.CommandRejectedException.class);
        assertThat(envelopes()).hasSize(before);
        DisclosureId id = s.w.draft();
        assertThat(s.text("SELECT agent_id FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", s.w.tenant.value(), id.value()))
                .isEqualTo(WorkflowSetup.AGENT_ID);
    }
}
