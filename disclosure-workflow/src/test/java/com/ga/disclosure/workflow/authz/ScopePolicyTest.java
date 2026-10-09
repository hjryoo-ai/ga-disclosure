package com.ga.disclosure.workflow.authz;

import com.ga.platform.core.tenant.AgentId;
import com.ga.platform.core.tenant.OrgPath;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 역할 × 행위 × 범위(6A 계획 §3.3, 설계서 §9 {@code authz-matrix})의 판정 — DB 없이. */
class ScopePolicyTest {

    static final AgentId A1 = AgentId.of("AGENT-1");
    static final AgentId A2 = AgentId.of("AGENT-2");
    static final TargetFacts MINE = new TargetFacts.OfDisclosure(A1, Optional.of(OrgPath.of("/HQ/B1")));
    static final TargetFacts THEIRS = new TargetFacts.OfDisclosure(A2, Optional.of(OrgPath.of("/HQ/B1/T2")));
    static final TargetFacts SIBLING = new TargetFacts.OfDisclosure(A2, Optional.of(OrgPath.of("/HQX/B1")));
    static final TargetFacts NO_ORG = new TargetFacts.OfDisclosure(A2, Optional.empty());

    static Principal agent() {
        return new Principal("agent-1", Set.of(Role.AGENT), Optional.of(A1), Optional.of(OrgPath.of("/HQ/B1")));
    }

    static Principal manager(String org) {
        return new Principal("manager-1", Set.of(Role.MANAGER), Optional.empty(), Optional.of(OrgPath.of(org)));
    }

    static Principal only(Role role) {
        return new Principal("svc", Set.of(role), Optional.empty(), Optional.empty());
    }

    @Test
    void anAgentReachesOnlyItsOwnDisclosure() {
        assertThat(ScopePolicy.permits(agent(), Channel.API, Action.SEAL, MINE)).contains(Role.AGENT);
        assertThat(ScopePolicy.permits(agent(), Channel.API, Action.SEAL, THEIRS)).isEmpty();
        assertThat(ScopePolicy.whyDenied(agent(), Channel.API, Action.SEAL, THEIRS)).isEqualTo(AuthorizationDenied.Reason.SCOPE);
    }

    @Test
    void creatingADraftNeedsAnAgentLink() {
        assertThat(ScopePolicy.permits(agent(), Channel.API, Action.DISCLOSURE_CREATE, new TargetFacts.Tenant())).contains(Role.AGENT);
        Principal unlinkedAgent = new Principal("x", Set.of(Role.AGENT), Optional.empty(), Optional.of(OrgPath.of("/HQ")));
        assertThat(ScopePolicy.permits(unlinkedAgent, Channel.API, Action.DISCLOSURE_CREATE, new TargetFacts.Tenant())).isEmpty();
    }

    /** 조직 범위는 세그먼트 접두다 — {@code /HQ}는 {@code /HQ/B1/T2}를 덮고 {@code /HQX/B1}은 덮지 않는다. 조직 없는 확인서는 관리자 범위 밖. */
    @Test
    void theManagerScopeIsASegmentPrefix() {
        assertThat(ScopePolicy.permits(manager("/HQ"), Channel.API, Action.DISCLOSURE_READ, THEIRS)).contains(Role.MANAGER);
        assertThat(ScopePolicy.permits(manager("/HQ/B1"), Channel.API, Action.DISCLOSURE_READ, THEIRS)).contains(Role.MANAGER);
        assertThat(ScopePolicy.permits(manager("/HQ"), Channel.API, Action.DISCLOSURE_READ, SIBLING)).isEmpty();
        assertThat(ScopePolicy.permits(manager("/HQ/B1/T2"), Channel.API, Action.DISCLOSURE_READ, MINE)).as("child does not cover parent").isEmpty();
        assertThat(ScopePolicy.permits(manager("/HQ"), Channel.API, Action.DISCLOSURE_READ, NO_ORG)).isEmpty();
    }

    @Test
    void complianceSeesTheWholeTenantButCannotDraft() {
        assertThat(ScopePolicy.permits(only(Role.COMPLIANCE), Channel.API, Action.ARTIFACT_VIEW, SIBLING)).contains(Role.COMPLIANCE);
        assertThat(ScopePolicy.permits(only(Role.COMPLIANCE), Channel.API, Action.ARTIFACT_VIEW, NO_ORG)).contains(Role.COMPLIANCE);
        assertThat(ScopePolicy.whyDenied(only(Role.COMPLIANCE), Channel.API, Action.SEAL, MINE)).isEqualTo(AuthorizationDenied.Reason.ROLE);
    }

    /** 주체가 역할을 여럿 가지면 선언 순서의 첫 허가 역할이 감사 행위자다. */
    @Test
    void theFirstGrantingRoleInDeclarationOrderIsTheActor() {
        Principal both = new Principal("p", Set.of(Role.AGENT, Role.COMPLIANCE, Role.MANAGER), Optional.of(A1), Optional.of(OrgPath.of("/HQ")));
        assertThat(ScopePolicy.permits(both, Channel.API, Action.DISCLOSURE_READ, MINE)).contains(Role.COMPLIANCE);
        assertThat(ScopePolicy.permits(both, Channel.API, Action.VOID, MINE)).contains(Role.MANAGER);
        assertThat(ScopePolicy.permits(both, Channel.API, Action.SEAL, MINE)).contains(Role.AGENT);
    }

    /** 승인 Q15: 사람 역할은 /internal에, 서비스 주체는 /api에 닿지 않는다(CHANNEL). */
    @Test
    void eachRoleReachesOnlyItsChannel() {
        assertThat(ScopePolicy.permits(agent(), Channel.INTERNAL, Action.SEAL, MINE)).isEmpty();
        assertThat(ScopePolicy.whyDenied(agent(), Channel.INTERNAL, Action.SEAL, MINE)).isEqualTo(AuthorizationDenied.Reason.CHANNEL);
        assertThat(ScopePolicy.permits(only(Role.SCHEDULER), Channel.INTERNAL, Action.DESTROY, new TargetFacts.Tenant())).contains(Role.SCHEDULER);
        assertThat(ScopePolicy.whyDenied(only(Role.SCHEDULER), Channel.API, Action.DESTROY, new TargetFacts.Tenant()))
                .isEqualTo(AuthorizationDenied.Reason.CHANNEL);
        assertThat(ScopePolicy.whyDenied(only(Role.FEED_CONSUMER), Channel.INTERNAL, Action.DESTROY, new TargetFacts.Tenant()))
                .isEqualTo(AuthorizationDenied.Reason.ROLE);
        for (Role r : Role.values()) {
            for (Channel c : Channel.values()) {
                if (c != r.channel()) {
                    for (Action a : Action.values()) {
                        assertThat(ScopePolicy.permits(new Principal("p", Set.of(r), Optional.of(A1), Optional.of(OrgPath.of("/HQ"))), c, a, MINE))
                                .as("%s over %s for %s", r, c, a).isEmpty();
                    }
                }
            }
        }
    }

    @Test
    void aMissingTargetIsNotFoundBeforeAnythingElse() {
        assertThat(ScopePolicy.permits(only(Role.COMPLIANCE), Channel.API, Action.DISCLOSURE_READ, new TargetFacts.Missing())).isEmpty();
        assertThat(ScopePolicy.whyDenied(only(Role.FEED_CONSUMER), Channel.API, Action.SEAL, new TargetFacts.Missing()))
                .isEqualTo(AuthorizationDenied.Reason.NOT_FOUND);
        Principal operator = new Principal("ops", Set.of(Role.OPERATOR), Optional.empty(), Optional.empty());
        assertThat(ScopePolicy.permits(operator, Channel.CLI, Action.JOB_READ, new TargetFacts.Missing())).as("ANY still needs a target").isEmpty();
        assertThat(ScopePolicy.whyDenied(operator, Channel.CLI, Action.JOB_READ, new TargetFacts.Missing()))
                .isEqualTo(AuthorizationDenied.Reason.NOT_FOUND);
    }

    /** 운영자 CLI는 서명 토큰·피드 밖의 모든 행위(범위 검사 없음), 고객 칸은 서명 행위의 SESSION뿐. */
    @Test
    void theOperatorAndCustomerColumnsAreClosed() {
        Map<Action, Map<Role, ScopePolicy.Scope>> m = ScopePolicy.matrix();
        assertThat(m.keySet()).containsExactlyInAnyOrder(Action.values());
        EnumSet<Action> customer = EnumSet.noneOf(Action.class);
        m.forEach((action, row) -> {
            assertThat(row).as("%s has a cell", action).isNotEmpty();
            if (row.containsKey(Role.CUSTOMER)) {
                customer.add(action);
                assertThat(row).as(action.name()).containsOnlyKeys(Role.CUSTOMER).containsEntry(Role.CUSTOMER, ScopePolicy.Scope.SESSION);
            } else if (!row.containsKey(Role.FEED_CONSUMER)) {
                assertThat(row).as(action.name()).containsEntry(Role.OPERATOR, ScopePolicy.Scope.ANY);
            }
            row.forEach((role, scope) -> assertThat(scope == ScopePolicy.Scope.ANY).as("%s %s", action, role).isEqualTo(role == Role.OPERATOR));
        });
        assertThat(customer).containsExactlyInAnyOrder(Action.SIGN_OPEN, Action.SIGN_VIEW_RECORD, Action.SIGN_VERIFY_IDENTITY, Action.SIGN_CAPTURE,
                Action.SIGN_STATUS);
        assertThat(ScopePolicy.permits(new Principal("ops", Set.of(Role.OPERATOR), Optional.empty(), Optional.empty()), Channel.CLI,
                Action.SUPERSEDE, SIBLING)).contains(Role.OPERATOR);
    }

    /**
     * 정정은 사람 칸이 없다(6B 승인 §2): 업무 규칙이 exceptionApproval.role을 요구해 설계사 칸은 업무 거부뿐이고, 관리자 칸은 승인이 거부했다(정정 버전의 agent_id는
     * 서명할 설계사여야 한다). 운영자 CLI 대리 실행만 남는다.
     */
    @Test
    void supersedeHasNoHumanCell() {
        assertThat(ScopePolicy.matrix().get(Action.SUPERSEDE)).containsOnlyKeys(Role.OPERATOR);
        assertThat(ScopePolicy.permits(agent(), Channel.API, Action.SUPERSEDE, MINE)).isEmpty();
        assertThat(ScopePolicy.permits(manager("/HQ"), Channel.API, Action.SUPERSEDE, MINE)).isEmpty();
        assertThat(ScopePolicy.matrix().get(Action.VALIDATE)).as("VALIDATE keeps its manager cell").containsKey(Role.MANAGER);
    }

    /** 목록 범위(6A 6c): 대상 판정 범위를 목록 조건으로 — 준법 테넌트, 관리자 조직, 설계사 자기 것. 범위에 필요한 연결이 없는 역할은 건너뛴다. */
    @Test
    void listScopesFollowTheSameCells() {
        assertThat(ScopePolicy.listScope(agent(), Channel.API, Action.DISCLOSURE_READ))
                .contains(new ScopePolicy.RoleScope(Role.AGENT, new ListScope.OwnedBy(A1)));
        assertThat(ScopePolicy.listScope(manager("/HQ"), Channel.API, Action.DISCLOSURE_READ))
                .contains(new ScopePolicy.RoleScope(Role.MANAGER, new ListScope.UnderOrg(OrgPath.of("/HQ"))));
        assertThat(ScopePolicy.listScope(only(Role.COMPLIANCE), Channel.API, Action.DISCLOSURE_READ))
                .contains(new ScopePolicy.RoleScope(Role.COMPLIANCE, new ListScope.WholeTenant()));
        assertThat(ScopePolicy.listScope(only(Role.OPERATOR), Channel.CLI, Action.DISCLOSURE_READ))
                .contains(new ScopePolicy.RoleScope(Role.OPERATOR, new ListScope.WholeTenant()));
        // 관리자 칸이 있어도 조직 연결이 없으면 목록 범위가 없다(빈 목록이 아니라 거부)
        assertThat(ScopePolicy.listScope(new Principal("m", java.util.Set.of(Role.MANAGER), Optional.empty(), Optional.empty()), Channel.API,
                Action.DISCLOSURE_READ)).isEmpty();
        assertThat(ScopePolicy.listScope(only(Role.SCHEDULER), Channel.INTERNAL, Action.DISCLOSURE_READ)).isEmpty();
        assertThat(ScopePolicy.listScope(only(Role.COMPLIANCE), Channel.INTERNAL, Action.DISCLOSURE_READ)).as("wrong channel").isEmpty();
        assertThat(ScopePolicy.listScope(agent(), Channel.API, Action.LEGAL_HOLD_READ)).isEmpty();
    }

    /** 6B 중간 회신 ②: 계약 피드는 자기 출처로만 — 본문 전의 대상 없는 검사는 출처가 하나라도 있어야 통과한다. */
    @Test
    void aContractFeedReachesOnlyItsOwnSources() {
        Principal feed = new Principal("feed-1", Set.of(Role.CONTRACT_FEED), Optional.empty(), Optional.empty(), Set.of("INS_FEED_A"));
        assertThat(ScopePolicy.permits(feed, Channel.INTERNAL, Action.CONTRACT_LINK_IMPORT, new TargetFacts.OfFeedSource("INS_FEED_A")))
                .contains(Role.CONTRACT_FEED);
        assertThat(ScopePolicy.permits(feed, Channel.INTERNAL, Action.CONTRACT_LINK_IMPORT, new TargetFacts.OfFeedSource("INS_FEED_B"))).isEmpty();
        assertThat(ScopePolicy.whyDenied(feed, Channel.INTERNAL, Action.CONTRACT_LINK_IMPORT, new TargetFacts.OfFeedSource("INS_FEED_B")))
                .isEqualTo(AuthorizationDenied.Reason.SCOPE);
        assertThat(ScopePolicy.permits(feed, Channel.INTERNAL, Action.CONTRACT_LINK_IMPORT, new TargetFacts.Tenant())).contains(Role.CONTRACT_FEED);
        assertThat(ScopePolicy.permits(only(Role.CONTRACT_FEED), Channel.INTERNAL, Action.CONTRACT_LINK_IMPORT, new TargetFacts.Tenant()))
                .as("a feed without sources").isEmpty();
        assertThat(ScopePolicy.listScope(feed, Channel.INTERNAL, Action.CONTRACT_LINK_IMPORT)).isEmpty();
    }
}
