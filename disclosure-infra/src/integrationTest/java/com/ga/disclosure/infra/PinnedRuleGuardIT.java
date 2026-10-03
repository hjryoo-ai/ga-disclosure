package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.rules.resolve.ResolutionFailure;
import com.ga.disclosure.rules.resolve.RuleResolutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 4 계획 승인 Q1 로더 조건: 고정 룰은 고정 ID로 로드하고(시행된 적이 있는 버전만 — RuleResolverTest), 로드한 유효 본문 해시가 초안 고정 때 감사에
 * 기록한 해시와 같아야 한다. 룰 행은 불변 트리거가 지키므로 정상 경로로는 다를 수 없다 — 트리거를 우회한 위조 본문과 고정 기록이 없는 확인서로
 * 이중 검사를 확인한다. 둘 다 명령 오류({@code COMMAND_FAILED}, 코드 {@code RULE_PINNED_BODY_MISMATCH})이고 상태는 그대로다.
 */
class PinnedRuleGuardIT {

    private final WorkflowSetup w = new WorkflowSetup();

    @AfterEach
    void close() {
        w.close();
    }

    private String status(DisclosureId id) {
        return w.db.asApp(w.tenant.value(), c -> {
            try (var ps = c.prepareStatement("SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?")) {
                ps.setString(1, w.tenant.value());
                ps.setObject(2, id.value());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    private void assertMismatchAndFailureAudited(DisclosureId id) {
        RuleResolutionException e = catchThrowableOfType(RuleResolutionException.class, () -> w.service.compare(w.tenant, WorkflowSetup.AGENT, id));
        assertThat(e.failure()).isEqualTo(ResolutionFailure.PINNED_BODY_MISMATCH);
        assertThat(w.auditLog()).anyMatch(r -> r.entry().action() == AuditAction.COMMAND_FAILED
                && r.entry().detail().path("code").asString().equals("RULE_PINNED_BODY_MISMATCH"));
    }

    @Test
    void aRuleBodyForgedPastItsGuardIsRefusedOnLoad() {
        DisclosureId id = w.draft();
        w.service.replaceItems(w.tenant, WorkflowSetup.AGENT, id, WorkflowSetup.threeItems());
        // 소유 롤이 불변 트리거를 끄고 고정 룰 본문을 바꿨다고 가정(정상 경로에는 없다)
        w.db.seed(w.tenant.value(), c -> {
            SeedData.exec(c, "ALTER TABLE rule_version DISABLE TRIGGER trg_rule_version_guard_update");
            SeedData.exec(c, "UPDATE rule_version SET body = jsonb_set(body, '{minCompare}', '2'::jsonb) WHERE tenant_id = ? AND rule_version_id = 'DISC-2026-07'",
                    w.tenant.value());
            SeedData.exec(c, "ALTER TABLE rule_version ENABLE TRIGGER trg_rule_version_guard_update");
        });
        assertMismatchAndFailureAudited(id);
        assertThat(status(id)).isEqualTo("DRAFT");
    }

    @Test
    void aDisclosureWithoutARecordedPinIsRefused() {
        // 감사 기록 없이 들어간 확인서(정상 경로에는 없다 — 생성은 언제나 같은 트랜잭션에서 DISCLOSURE_CREATE를 남긴다)
        UUID id = UUID.randomUUID();
        w.db.seed(w.tenant.value(), c -> SeedData.exec(c, """
                INSERT INTO disclosure (tenant_id, disclosure_id, org_path, agent_id, customer_ref, group_code, template_id, template_version,
                                        rule_version_id, issuer_mode, status, consult_date)
                VALUES (?, ?, '/HQ/B1', ?, ?, 'PG-HEALTH-SIMPLE-NR', 'STANDARD', 1, 'DISC-2026-07', 'SELF', 'DRAFT', DATE '2026-09-23')
                """, w.tenant.value(), id, WorkflowSetup.AGENT_ID, w.customer.value()));
        assertMismatchAndFailureAudited(DisclosureId.of(id));
    }

    @Test
    void theNormalPathPinsAndLoadsTheSameBody() {
        DisclosureId id = w.reasoned();
        assertThat(status(id)).isEqualTo("REASONED");                     // 생성 → 항목 → 비교 → 산출 → 사유: 매 명령이 로드 검사를 통과했다
    }
}
