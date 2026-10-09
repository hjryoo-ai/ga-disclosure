package com.ga.disclosure.infra;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.persistence.VerifyEvidenceRepository;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.flag.FlagCommandService;
import com.ga.disclosure.workflow.flag.FlagRejectedException;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G1(6B 지시문 §2, 계획 §7): 플래그의 담당 역할·SLA 기한·설계사 가시성·수동 해소 코드는 룰 {@code complianceQueue.types}만 바꿔 바뀐다(코드 diff 0).
 * 담당·가시성·기한은 플래그가 열릴 때 복사해 고정하고(SLA null이면 {@code due_at} NULL), 해소 코드는 해소 시점의 룰이다. SLA 경과 표시는 컬럼 + 감사이고
 * 새 플래그를 만들지 않는다. 배정은 그 담당 역할로 연결된 주체에게만, 관리자는 담당 역할이 MANAGER인 플래그만.
 */
class ComplianceQueueRulesIT {

    static final Actor COMPLIANCE = SealSetup.COMPLIANCE;
    static final Actor OPERATOR = new Actor("ops-queue@test", "OPERATOR");

    static FlagCommandService commands(WorkflowSetup w, Clock clock) {
        return new FlagCommandService(w.flags, w.flags, new VerifyEvidenceRepository(w.gateway), w.agents, new RuleResolver(w.rules), w.audit, w.tx,
                Callers.authz(clock), clock);
    }

    static UUID unattached(WorkflowSetup w, DisclosureFlagPort.Type type) {
        return w.in(() -> w.flags.raiseUnattached(type, "HIGH", "TENANT", UUID.randomUUID().toString(), w.clock.instant())).flagId();
    }

    static UUID onDisclosure(WorkflowSetup w, DisclosureFlagPort.Type type, DisclosureId id) {
        return w.in(() -> w.flags.raise(type, "HIGH", id, "DISCLOSURE", id.toString(), w.clock.instant())).flagId();
    }

    /** 플래그 행의 복사 값: 담당|가시성|기한(없으면 -). */
    static String copied(WorkflowSetup w, UUID flag) {
        return w.db.asApp(w.tenant.value(), c -> SeedData.call(c, """
                SELECT assigned_role || '|' || visible_to_agent || '|' || coalesce(to_char(due_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '-')
                  FROM compliance_flag WHERE tenant_id = ? AND flag_id = ?""", w.tenant.value(), flag));
    }

    static Consumer<ObjectNode> signExpired(String role, Integer slaHours, boolean visible, String... codes) {
        return body -> {
            ObjectNode p = (ObjectNode) body.at("/complianceQueue/types/SIGN_EXPIRED");
            p.put("assignedRole", role).put("visibleToAgent", visible);
            if (slaHours == null) {
                p.putNull("slaHours");
            } else {
                p.put("slaHours", slaHours);
            }
            ArrayNode list = p.putArray("resolutionCodes");
            for (String c : codes) {
                list.addObject().put("code", c).put("label", "시험 " + c);
            }
        };
    }

    static FlagRejectedException.Rejection rejected(Runnable r) {
        try {
            r.run();
        } catch (FlagRejectedException e) {
            return e.rejection();
        }
        throw new AssertionError("expected a rejection");
    }

    @Test
    void theBundlePolicyIsCopiedWhenAFlagOpens() {
        try (WorkflowSetup w = new WorkflowSetup()) {
            UUID expired = unattached(w, DisclosureFlagPort.Type.SIGN_EXPIRED);
            assertThat(copied(w, expired)).as("bundle: COMPLIANCE, hidden, SLA null → no due").isEqualTo("COMPLIANCE|false|-");
            UUID scan = unattached(w, DisclosureFlagPort.Type.PAPER_SCAN_REVIEW);
            assertThat(copied(w, scan)).isEqualTo("MANAGER|false|-");
        }
    }

    @Test
    void changingOnlyTheRuleChangesRoleVisibilityDueAndCodes() {
        try (WorkflowSetup w = WorkflowSetup.withRule(signExpired("MANAGER", 48, true, "CUSTOM_CLOSE"))) {
            UUID flag = unattached(w, DisclosureFlagPort.Type.SIGN_EXPIRED);
            Instant due = w.clock.instant().plus(Duration.ofHours(48));
            assertThat(copied(w, flag)).isEqualTo("MANAGER|true|" + due);
            FlagCommandService c = commands(w, w.clock);
            // 번들의 해소 코드(REISSUED)는 이 룰에 없다 — 룰의 코드로만 닫힌다
            assertThat(rejected(() -> c.resolve(Callers.of(w.tenant, COMPLIANCE), flag, "REISSUED", Optional.empty())))
                    .isEqualTo(FlagRejectedException.Rejection.RESOLUTION_CODE_UNKNOWN);
            assertThat(c.resolve(Callers.of(w.tenant, COMPLIANCE), flag, "CUSTOM_CLOSE", Optional.empty()).resolutionCode()).isEqualTo("CUSTOM_CLOSE");
            assertThat(w.db.<String>asApp(w.tenant.value(), x -> SeedData.call(x,
                    "SELECT resolution || '|' || resolution_code FROM compliance_flag WHERE tenant_id = ? AND flag_id = ?", w.tenant.value(), flag)))
                    .isEqualTo("COMPLIANCE_RESOLVED|CUSTOM_CLOSE");
            assertThat(rejected(() -> c.resolve(Callers.of(w.tenant, COMPLIANCE), flag, "CUSTOM_CLOSE", Optional.empty())))
                    .isEqualTo(FlagRejectedException.Rejection.ALREADY_RESOLVED);
        }
    }

    @Test
    void manualResolutionFollowsTheRuleShape() {
        try (WorkflowSetup w = new WorkflowSetup()) {
            FlagCommandService c = commands(w, w.clock);
            UUID scan = unattached(w, DisclosureFlagPort.Type.PAPER_SCAN_REVIEW);
            assertThat(rejected(() -> c.resolve(Callers.of(w.tenant, COMPLIANCE), scan, "PAPER_SCAN_REVIEWED", Optional.empty())))
                    .as("empty resolutionCodes = closed only by the review use case").isEqualTo(FlagRejectedException.Rejection.NOT_MANUALLY_RESOLVABLE);
            UUID expired = unattached(w, DisclosureFlagPort.Type.SIGN_EXPIRED);
            assertThat(rejected(() -> c.resolve(Callers.of(w.tenant, COMPLIANCE), expired, "REISSUED",
                    Optional.of(Canonicalizer.parseStrict("{\"note\":\"free text\"}")))))
                    .as("no free-form evidence is stored").isEqualTo(FlagRejectedException.Rejection.EVIDENCE_NOT_ACCEPTED);
            assertThat(c.resolve(Callers.of(w.tenant, COMPLIANCE), expired, "REISSUED", Optional.empty()).type()).isEqualTo("SIGN_EXPIRED");
            // 거부는 감사에 남고(커밋), 성공은 FLAG_RESOLVE
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == com.ga.disclosure.audit.AuditAction.FLAG_COMMAND_REJECTED)
                    .extracting(a -> a.entry().detail().get("rejected").asString()).containsExactly("NOT_MANUALLY_RESOLVABLE", "EVIDENCE_NOT_ACCEPTED");
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == com.ga.disclosure.audit.AuditAction.FLAG_RESOLVE)
                    .extracting(a -> a.entry().targetId()).containsExactly(expired.toString());
        }
    }

    @Test
    void slaBreachIsAColumnAndAnAuditRowNotANewFlag() {
        try (WorkflowSetup w = WorkflowSetup.withRule(signExpired("COMPLIANCE", 1, false, "REISSUED"))) {
            UUID due = unattached(w, DisclosureFlagPort.Type.SIGN_EXPIRED);
            UUID noSla = unattached(w, DisclosureFlagPort.Type.NOTIFY_FAILED);
            int flagsBefore = count(w, "SELECT count(*)::text FROM compliance_flag WHERE tenant_id = ?");
            Clock early = Clock.fixed(w.clock.instant().plus(Duration.ofMinutes(59)), w.clock.getZone());
            assertThat(commands(w, early).sweepSla(Callers.cli(w.tenant, OPERATOR), 100).breached()).isEmpty();
            Clock late = Clock.fixed(w.clock.instant().plus(Duration.ofHours(2)), w.clock.getZone());
            FlagCommandService.SweepReport r = commands(w, late).sweepSla(Callers.cli(w.tenant, OPERATOR), 100);
            assertThat(r.breached()).extracting(b -> b.flagId()).containsExactly(due);
            assertThat(commands(w, late).sweepSla(Callers.cli(w.tenant, OPERATOR), 100).breached()).as("marked once").isEmpty();
            assertThat(count(w, "SELECT count(*)::text FROM compliance_flag WHERE tenant_id = ?")).isEqualTo(flagsBefore);
            assertThat(w.db.<String>asApp(w.tenant.value(), x -> SeedData.call(x,
                    "SELECT (sla_breached_at IS NOT NULL)::text FROM compliance_flag WHERE tenant_id = ? AND flag_id = ?", w.tenant.value(), noSla)))
                    .isEqualTo("false");
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == com.ga.disclosure.audit.AuditAction.FLAG_SLA_BREACHED)
                    .extracting(a -> a.entry().targetId()).containsExactly(due.toString());
            // 배치는 사람 역할에 없다
            assertThatThrownBy(() -> commands(w, late).sweepSla(Callers.of(w.tenant, COMPLIANCE), 100))
                    .isInstanceOf(com.ga.disclosure.workflow.authz.AuthorizationDenied.class);
        }
    }

    @Test
    void assignmentGoesToASubjectLinkedInTheAssignedRole() {
        try (WorkflowSetup w = new WorkflowSetup()) {
            w.db.seed(w.tenant.value(), x -> SeedData.roleLink(x, w.tenant.value(), "compliance-2@test", "COMPLIANCE"));
            FlagCommandService c = commands(w, w.clock);
            DisclosureId d = w.draft();
            UUID proxy = onDisclosure(w, DisclosureFlagPort.Type.SIGNATURE_DEVICE_REUSE, d);
            UUID scan = onDisclosure(w, DisclosureFlagPort.Type.PAPER_SCAN_REVIEW, d);
            assertThat(rejected(() -> c.assign(Callers.of(w.tenant, COMPLIANCE), proxy, WorkflowSetup.AGENT.subject())))
                    .isEqualTo(FlagRejectedException.Rejection.ASSIGNEE_INVALID);
            assertThat(c.assign(Callers.of(w.tenant, COMPLIANCE), proxy, "compliance-2@test").assignee()).isEqualTo("compliance-2@test");
            // 관리자: 조직 아래의 확인서 플래그 중 담당 역할이 MANAGER인 것만
            assertThat(rejected(() -> c.assign(Callers.of(w.tenant, WorkflowSetup.MANAGER), proxy, WorkflowSetup.MANAGER.subject())))
                    .isEqualTo(FlagRejectedException.Rejection.ROLE_NOT_ASSIGNED);
            c.assign(Callers.of(w.tenant, WorkflowSetup.MANAGER), scan, WorkflowSetup.MANAGER.subject());
            assertThat(w.db.<String>asApp(w.tenant.value(), x -> SeedData.call(x,
                    "SELECT string_agg(assignee, ',' ORDER BY assignee) FROM compliance_flag WHERE tenant_id = ? AND assignee IS NOT NULL", w.tenant.value())))
                    .isEqualTo("compliance-2@test," + WorkflowSetup.MANAGER.subject());
            // 테넌트 수준 플래그는 관리자에게 없는 대상(404)
            UUID tenantLevel = unattached(w, DisclosureFlagPort.Type.NOTIFY_FAILED);
            assertThatThrownBy(() -> c.assign(Callers.of(w.tenant, WorkflowSetup.MANAGER), tenantLevel, WorkflowSetup.MANAGER.subject()))
                    .isInstanceOf(com.ga.disclosure.workflow.authz.AuthorizationDenied.class);
            // 설계사는 칸이 없다
            assertThatThrownBy(() -> c.assign(Callers.of(w.tenant, WorkflowSetup.AGENT), scan, WorkflowSetup.MANAGER.subject()))
                    .isInstanceOf(com.ga.disclosure.workflow.authz.AuthorizationDenied.class);
        }
    }

    static int count(WorkflowSetup w, String sql) {
        return Integer.parseInt(w.db.<String>asApp(w.tenant.value(), x -> SeedData.call(x, sql, w.tenant.value())));
    }
}
