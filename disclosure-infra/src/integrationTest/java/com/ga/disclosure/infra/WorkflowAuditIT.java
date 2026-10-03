package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditChain;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.disclosure.IllegalTransition;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.infra.engine.EngineGradeClient;
import com.ga.disclosure.infra.engine.HttpEngineTransport;
import com.ga.disclosure.infra.persistence.TenantRepository;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.validation.standard.StandardValidations;
import com.ga.disclosure.workflow.disclosure.CommandResult;
import com.ga.disclosure.workflow.disclosure.DisclosureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 3A W7(감사 실패 기록 규약, 계획 §4·승인 B2): 전이·검증·엔진 호출은 업무 행과 같은 트랜잭션의 감사 행으로 남고(감사 뒤 예외를 주입하면
 * 업무 행도 없다), 업무 거부는 상태 불변 + 감사 커밋, 명령 오류는 업무 행·업무 감사 없이 같은 테넌트의 {@code COMMAND_FAILED} 1행(메시지
 * 없음), 테넌트 미바인딩 명령은 감사를 남기지 않는다. 체인은 끝까지 끊기지 않는다.
 */
class WorkflowAuditIT {

    private final WorkflowSetup s = new WorkflowSetup();

    @AfterEach
    void stop() {
        s.close();
    }

    private List<AuditRecord> rowsOf(DisclosureId id) {
        return s.auditLog().stream().filter(a -> id.toString().equals(a.entry().targetId())).toList();
    }

    private String status(DisclosureId id) {
        return s.db.asApp(s.tenant.value(), c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?")) {
                ps.setString(1, s.tenant.value());
                ps.setObject(2, id.value());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    /** 전 테넌트(RLS 밖, 슈퍼유저)의 COMMAND_FAILED 행 수. */
    private long globalCommandFailed() {
        try (var c = s.db.superuserDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM audit_log WHERE action = 'COMMAND_FAILED'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everySuccessfulCommandLeavesItsAuditRowsAndTheChainHolds() {
        DisclosureId id = s.compared();
        s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id);
        s.service.setRecommendations(s.tenant, WorkflowSetup.AGENT, id, List.of(new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null),
                new AgentReason(3, List.of(ReasonCode.of("COVERAGE")), null)));
        assertThat(rowsOf(id)).extracting(a -> a.entry().action()).containsExactly(
                AuditAction.DISCLOSURE_CREATE,
                AuditAction.DISCLOSURE_TRANSITION,                                    // DRAFT 항목 교체(검증 없음)
                AuditAction.DISCLOSURE_VALIDATE, AuditAction.DISCLOSURE_TRANSITION,   // 비교
                AuditAction.GRADE_FETCH, AuditAction.DISCLOSURE_VALIDATE, AuditAction.DISCLOSURE_TRANSITION,
                AuditAction.DISCLOSURE_VALIDATE, AuditAction.DISCLOSURE_TRANSITION);
        assertThat(AuditChain.breaks(s.auditLog())).isEmpty();
    }

    @Test
    void anExceptionAfterTheAuditRowRollsTheBusinessChangeBackAndRecordsTheFailure() {
        DisclosureId id = s.compared();
        AuditPort failingAfterTransition = new AuditPort() {
            @Override
            public AuditRecord append(AuditEntry entry) {
                AuditRecord r = s.audit.append(entry);
                if (entry.action() == AuditAction.DISCLOSURE_TRANSITION) {
                    throw new IllegalStateException("injected after the audit insert");
                }
                return r;
            }

            @Override
            public List<AuditRecord> readAll() {
                return s.audit.readAll();
            }

            @Override
            public List<AuditRecord> readAfter(long afterSeq, int limit) {
                return s.audit.readAfter(afterSeq, limit);
            }

            @Override
            public List<AuditRecord> readTarget(String targetKind, String targetId) {
                return s.audit.readTarget(targetKind, targetId);
            }
        };
        DisclosureService failing = new DisclosureService(s.disclosures, s.reviews, s.flags, new TenantRepository(s.gateway),
                new EngineGradeClient(new HttpEngineTransport(s.settings, t -> Optional.of(WorkflowSetup.TOKEN), t -> s.engine.baseUrl())),
                s.catalog, s.catalog, s.vault, new RuleResolver(s.rules), new TemplateResolver(s.templates), StandardValidations.registry(),
                failingAfterTransition, s.tx, s.clock, s.agents, s.outbox);
        int before = rowsOf(id).size();
        assertThatThrownBy(() -> failing.requestGrades(s.tenant, WorkflowSetup.AGENT, id)).hasMessageContaining("injected");
        assertThat(status(id)).as("업무 행도 롤백").isEqualTo("COMPARED");
        List<AuditRecord> after = rowsOf(id);
        assertThat(after).hasSize(before + 1);
        AuditRecord failed = after.getLast();
        assertThat(failed.entry().action()).isEqualTo(AuditAction.COMMAND_FAILED);
        assertThat(failed.tenantId()).isEqualTo(s.tenant);
        assertThat(failed.entry().detail().get("command").asString()).isEqualTo("APPLY_SNAPSHOT");
        assertThat(failed.entry().detail().get("code").asString()).isEqualTo("UNEXPECTED");
        assertThat(failed.entry().detail().propertyNames()).as("메시지 없음").containsExactlyInAnyOrder("command", "exception", "code");
        assertThat(AuditChain.breaks(s.auditLog())).isEmpty();
    }

    @Test
    void aBusinessRejectionCommitsItsAuditRowsWithoutChangingState() {
        DisclosureId id = s.draft();
        s.service.replaceItems(s.tenant, WorkflowSetup.AGENT, id, WorkflowSetup.threeItems().subList(0, 1));
        int before = rowsOf(id).size();
        CommandResult r = s.service.compare(s.tenant, WorkflowSetup.AGENT, id);
        assertThat(r.applied()).isFalse();
        assertThat(status(id)).isEqualTo("DRAFT");
        assertThat(rowsOf(id).subList(before, rowsOf(id).size())).extracting(a -> a.entry().action())
                .containsExactly(AuditAction.DISCLOSURE_VALIDATE, AuditAction.DISCLOSURE_REJECT);
    }

    @Test
    void aCommandErrorLeavesOnlyCommandFailedInTheSameTenant() {
        DisclosureId id = s.draft();
        int before = rowsOf(id).size();
        IllegalTransition e = catchThrowableOfType(IllegalTransition.class, () -> s.service.setRecommendations(s.tenant, WorkflowSetup.AGENT,
                id, List.of(new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null))));
        assertThat(e.from()).isEqualTo(DisclosureStatus.DRAFT);
        List<AuditRecord> added = rowsOf(id).subList(before, rowsOf(id).size());
        assertThat(added).singleElement().satisfies(a -> {
            assertThat(a.entry().action()).isEqualTo(AuditAction.COMMAND_FAILED);
            assertThat(a.entry().detail().get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
            assertThat(a.entry().detail().get("exception").asString()).isEqualTo("IllegalTransition");
            assertThat(a.entry().actorSubject()).isEqualTo(WorkflowSetup.AGENT.subject());
        });
    }

    @Test
    void anUnboundTenantIsNeverAudited() {
        DisclosureId id = s.draft();
        long before = globalCommandFailed();
        assertThatThrownBy(() -> s.service.compare(null, WorkflowSetup.AGENT, id)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not bound");
        assertThat(globalCommandFailed()).isEqualTo(before);
    }
}
