package com.ga.disclosure.infra.authz;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.infra.persistence.AuthzFactsRepository;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationDenied;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Channel;
import com.ga.disclosure.workflow.authz.Principal;
import com.ga.disclosure.workflow.authz.Role;
import com.ga.disclosure.workflow.authz.ScopePolicy;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.TargetFacts;
import com.ga.disclosure.workflow.identity.AgentDirectory;
import com.ga.platform.core.tenant.TenantContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link AuthorizationPort} 어댑터(6A 계획 §3.2). 유스케이스의 트랜잭션 안(RLS 바인딩 뒤)에서 부른다.
 * <ul>
 *   <li><b>API·INTERNAL</b>: {@code identity_link(테넌트, 주체)} 행에서 역할·설계사·조직을 정한다 — 토큰 클레임은 주체·테넌트 외에 읽지 않는다(절대
 *       규칙 5). 행이 없으면 {@code NO_LINK}. 매 요청 해석, 캐시 없음.</li>
 *   <li><b>CLI</b>: 역할 OPERATOR, 범위 검사 없음(승인 Q9) — 단 대상은 있어야 한다(없으면 다른 채널과 같은 {@code NOT_FOUND}). 표가 OPERATOR를 허가하지 않는 행위(서명 토큰·피드)는 {@code CHANNEL}.</li>
 *   <li><b>SIGN_TOKEN</b>: 토큰이 곧 자격이다(유스케이스가 토큰을 먼저 대조했다). 대상은 토큰이 가리키는 세션 하나 — 세션이 있는지만 본다.</li>
 * </ul>
 * 거부는 {@link AuthorizationDenied} 하나이고, 감사 {@code AUTHZ_DENIED}(행위·채널·사유, 대상 종류·ID)를 <b>별도 트랜잭션</b>에 남긴다(유스케이스
 * 트랜잭션은 롤백된다). 테넌트 행이 없으면 남기지 않는다.
 */
public final class IdentityLinkAuthorization implements AuthorizationPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AgentDirectory links;
    private final AuthzFactsRepository facts;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;

    public IdentityLinkAuthorization(AgentDirectory links, AuthzFactsRepository facts, AuditPort audit, WorkflowTransactions transactions,
                                     Clock clock) {
        this.links = Objects.requireNonNull(links, "links");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Actor require(Caller caller, Action action, Target target) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(target, "target");
        if (!caller.tenant().equals(TenantContext.current())) {
            throw new IllegalStateException("authorization runs inside the caller's tenant transaction");
        }
        Principal principal;
        TargetFacts targetFacts;
        switch (caller.channel()) {
            case CLI -> {
                principal = new Principal(caller.subject(), Set.of(Role.OPERATOR), Optional.empty(), Optional.empty());
                targetFacts = facts.facts(target);                            // 범위 검사는 없지만 없는 대상은 모든 채널에서 NOT_FOUND
            }
            case SIGN_TOKEN -> {
                principal = new Principal(caller.subject(), Set.of(Role.CUSTOMER), Optional.empty(), Optional.empty());
                targetFacts = target instanceof Target.Session ? facts.facts(target) : new TargetFacts.Missing();
            }
            case API, INTERNAL -> {
                Optional<AgentDirectory.LinkedIdentity> link = links.find(caller.subject());
                if (link.isEmpty()) {
                    throw denied(caller, action, target, AuthorizationDenied.Reason.NO_LINK);
                }
                principal = principal(link.get());
                targetFacts = facts.facts(target);
            }
            default -> throw new IllegalStateException("unknown channel " + caller.channel());
        }
        Optional<Role> granted = ScopePolicy.permits(principal, caller.channel(), action, targetFacts);
        if (granted.isEmpty()) {
            throw denied(caller, action, target, ScopePolicy.whyDenied(principal, caller.channel(), action, targetFacts));
        }
        return new Actor(caller.subject(), granted.get().name());
    }

    private static Principal principal(AgentDirectory.LinkedIdentity link) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (Role r : Role.values()) {
            if (r.linkable() && link.hasRole(r.name())) {
                roles.add(r);
            }
        }
        return new Principal(link.subject(), roles, link.agentId(), link.orgPath());
    }

    private AuthorizationDenied denied(Caller caller, Action action, Target target, AuthorizationDenied.Reason reason) {
        AuthorizationDenied denial = new AuthorizationDenied(action, reason);
        if (caller.channel() == Channel.API || caller.channel() == Channel.INTERNAL) {
            try {
                transactions.inNewTenantTransaction(caller.tenant(), () -> {
                    if (!facts.tenantExists()) {
                        return null;
                    }
                    ObjectNode detail = JSON.createObjectNode().put("action", action.name()).put("channel", caller.channel().name())
                            .put("reason", reason.name());
                    return audit.append(new AuditEntry(clock.instant(), caller.subject(), "UNAUTHORIZED", AuditAction.AUTHZ_DENIED, target.kind(),
                            target.idOrNull(), detail));
                });
            } catch (RuntimeException recordFailure) {
                denial.addSuppressed(recordFailure);
            }
        }
        return denial;
    }
}
