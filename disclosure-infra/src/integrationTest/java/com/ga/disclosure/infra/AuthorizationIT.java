package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.authz.AuthorizationDenied;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 인가 어댑터(6A 계획 §3.2, 설계서 §9 인가 모델)를 실제 DB로: 역할·설계사·조직은 {@code identity_link}에서, 대상 사실은 RLS 아래에서. 거부는 사유와
 * 무관하게 같은 예외이고, 거부 감사 {@code AUTHZ_DENIED}는 유스케이스 트랜잭션이 롤백돼도 남는다(별도 트랜잭션). 거부는 {@code COMMAND_FAILED}가 아니다.
 */
class AuthorizationIT {

    final WorkflowSetup s = new WorkflowSetup();

    @AfterEach
    void close() {
        s.close();
    }

    void link(String subject, String agentIdOrNull, String orgOrNull, String role) {
        s.db.seed(s.tenant.value(), c -> SeedData.exec(c, "INSERT INTO identity_link (tenant_id, subject, agent_id, roles, org_path) VALUES (?, ?, ?, ARRAY[?], ?)",
                s.tenant.value(), subject, agentIdOrNull, role, orgOrNull));
    }

    AuthorizationDenied.Reason denied(Executable call) {
        Throwable[] caught = new Throwable[1];
        assertThatThrownBy(() -> call.execute()).isInstanceOf(AuthorizationDenied.class).hasMessage("not authorized")
                .satisfies(e -> caught[0] = e);
        return ((AuthorizationDenied) caught[0]).reason();
    }

    List<AuditRecord> denials() {
        return s.auditLog().stream().filter(r -> r.entry().action() == AuditAction.AUTHZ_DENIED).toList();
    }

    long count(String sql, Object... params) {
        try (var c = s.db.superuserDataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anUnlinkedSubjectIsDeniedAndTheDenialOutlivesTheRollback() {
        DisclosureId id = s.draft();
        long failedBefore = count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'COMMAND_FAILED'", s.tenant.value());
        assertThat(denied(() -> s.service.compare(Caller.api(s.tenant, "nobody@test"), id))).isEqualTo(AuthorizationDenied.Reason.NO_LINK);
        assertThat(denials()).singleElement().satisfies(r -> {
            assertThat(r.entry().actorSubject()).isEqualTo("nobody@test");
            assertThat(r.entry().actorRole()).isEqualTo("UNAUTHORIZED");
            assertThat(r.entry().targetKind()).isEqualTo("DISCLOSURE");
            assertThat(r.entry().targetId()).isEqualTo(id.toString());
            assertThat(r.entry().detail().propertyNames()).containsExactlyInAnyOrder("action", "channel", "reason");
            assertThat(r.entry().detail().get("action").asString()).isEqualTo("COMPARE");
            assertThat(r.entry().detail().get("channel").asString()).isEqualTo("API");
            assertThat(r.entry().detail().get("reason").asString()).isEqualTo("NO_LINK");
        });
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'COMMAND_FAILED'", s.tenant.value()))
                .as("a denial is not a failed command").isEqualTo(failedBefore);
        assertThat(count("SELECT count(*) FROM disclosure WHERE tenant_id = ? AND disclosure_id = ? AND status = 'DRAFT'", s.tenant.value(),
                id.value())).isEqualTo(1);
    }

    @Test
    void aRoleWithoutTheCellIsDenied() {
        DisclosureId id = s.reasoned();
        assertThat(denied(() -> s.service.replaceItems(Callers.of(s.tenant, SealSetup.COMPLIANCE), id, WorkflowSetup.threeItems())))
                .isEqualTo(AuthorizationDenied.Reason.ROLE);
    }

    @Test
    void anotherAgentIsOutOfScope() {
        link("agent-2@test", "AGENT-2", "/HQ/B1", "AGENT");
        DisclosureId id = s.draft();
        assertThat(denied(() -> s.service.replaceItems(Caller.api(s.tenant, "agent-2@test"), id, WorkflowSetup.threeItems())))
                .isEqualTo(AuthorizationDenied.Reason.SCOPE);
        assertThat(s.service.replaceItems(Callers.of(s.tenant, WorkflowSetup.AGENT), id, WorkflowSetup.threeItems()).applied()).isTrue();
    }

    /** 조직은 세그먼트 접두: /HQ 관리자는 /HQ/B1 확인서에 닿고 /HQX 관리자는 닿지 않는다. 감사 행위자는 허가를 준 역할. */
    @Test
    void theManagerScopeIsTheOrgPathSegmentPrefix() {
        link("manager-hq@test", null, "/HQ", "MANAGER");
        link("manager-hqx@test", null, "/HQX", "MANAGER");
        DisclosureId id = s.reasoned();
        assertThat(denied(() -> s.service.sealBlockers(Caller.api(s.tenant, "manager-hqx@test"), id))).isEqualTo(AuthorizationDenied.Reason.SCOPE);
        s.service.sealBlockers(Caller.api(s.tenant, "manager-hq@test"), id);
        assertThat(s.auditLog()).anyMatch(r -> r.entry().action() == AuditAction.DISCLOSURE_VALIDATE
                && r.entry().actorSubject().equals("manager-hq@test") && r.entry().actorRole().equals("MANAGER"));
    }

    /** 승인 Q15: 사람 역할은 /internal 채널로 닿지 않는다. */
    @Test
    void aHumanRoleOverTheInternalChannelIsDenied() {
        DisclosureId id = s.draft();
        assertThat(denied(() -> s.service.compare(Caller.internal(s.tenant, WorkflowSetup.AGENT.subject()), id)))
                .isEqualTo(AuthorizationDenied.Reason.CHANNEL);
    }

    /** 다른 테넌트의 확인서는 없는 대상과 같다(NOT_FOUND) — 감사는 호출자 테넌트에만, 대상 테넌트에는 흔적이 없다. */
    @Test
    void anotherTenantsDisclosureIsNotFound() {
        try (WorkflowSetup other = new WorkflowSetup()) {
            DisclosureId theirs = other.draft();
            assertThat(denied(() -> s.service.compare(Callers.of(s.tenant, WorkflowSetup.AGENT), theirs)))
                    .isEqualTo(AuthorizationDenied.Reason.NOT_FOUND);
            assertThat(denials()).singleElement().satisfies(r -> assertThat(r.entry().targetId()).isEqualTo(theirs.toString()));
            assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'AUTHZ_DENIED'", other.tenant.value())).isZero();
            assertThat(count("SELECT count(*) FROM disclosure WHERE tenant_id = ? AND disclosure_id = ? AND status = 'DRAFT'",
                    other.tenant.value(), theirs.value())).isEqualTo(1);
        }
    }

    /** 테넌트 행이 없으면 거부 감사를 남기지 않는다(위조 테넌트 이름으로 행을 만들지 않는다). */
    @Test
    void anUnknownTenantLeavesNoAuditRow() {
        TenantId ghost = TenantId.of(SeedData.uniqueTenant("GHOST"));
        assertThat(denied(() -> s.service.compare(Caller.api(ghost, WorkflowSetup.AGENT.subject()), DisclosureId.of(java.util.UUID.randomUUID()))))
                .isEqualTo(AuthorizationDenied.Reason.NO_LINK);
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ?", ghost.value())).isZero();
    }

    /** CLI는 OPERATOR(범위 검사 없음, 승인 Q9) — 거부도 감사도 없이 통과하고 감사 역할은 OPERATOR. */
    @Test
    void theCliCallerIsTheOperator() {
        DisclosureId id = s.draft();
        s.service.replaceItems(Caller.cli(s.tenant, "ops@test"), id, WorkflowSetup.threeItems());
        assertThat(s.auditLog()).anyMatch(r -> r.entry().action() != AuditAction.AUTHZ_DENIED
                && r.entry().actorSubject().equals("ops@test") && r.entry().actorRole().equals("OPERATOR"));
        assertThat(denials()).isEmpty();
        // 범위 검사는 없지만 없는 대상은 다른 채널과 같은 NOT_FOUND(CLI 거부는 감사하지 않는다 — 운영자 콘솔 오류)
        assertThat(denied(() -> s.service.compare(Caller.cli(s.tenant, "ops@test"), DisclosureId.of(java.util.UUID.randomUUID()))))
                .isEqualTo(AuthorizationDenied.Reason.NOT_FOUND);
        assertThat(denials()).isEmpty();
    }
}
