package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.enums.SignOrder;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.validation.ValidationSubject;
import com.ga.disclosure.sign.session.SessionStatus;
import com.ga.disclosure.sign.session.SessionWindow;
import com.ga.disclosure.sign.token.SignToken;
import com.ga.disclosure.sign.token.SignTokenRejected;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Channel;
import com.ga.disclosure.workflow.disclosure.DisclosureLoader.Loaded;
import com.ga.disclosure.workflow.identity.AgentDirectory;
import com.ga.disclosure.workflow.sign.SignRejection;
import com.ga.disclosure.workflow.sign.SignSession;
import com.ga.disclosure.workflow.sign.SignSessionStore;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 서명 유스케이스들(세션·서명)이 함께 쓰는 판정과 기록(4 계획 §2·§7).
 *
 * <p><b>토큰 접근</b>: 토큰 해시로 세션을 찾고(잠그지 않음) → 확인서 행 잠금(로더) → 세션 행 잠금 → OPEN·TTL 검사. 모르는 토큰·닫힌 세션·TTL 경과는
 * 전부 {@link Access.Denied}이고 호출자는 트랜잭션을 끝낸 뒤 {@link SignTokenRejected} 하나로 응답한다(승인 B2 — 원인 구분 없음). 거부 사실은 사유
 * 코드만 감사 {@code SIGN_SESSION_DENIED}로 남는다(세션 행은 바꾸지 않는다, 4 계획 §2.3) — 별도 트랜잭션이고, 접두가 없는 테넌트면 남기지 않는다
 * (감사 로그에는 테넌트 외래 키가 없다 — 위조 접두로 남의 이름의 행을 만들 수 없게 테넌트 행을 먼저 확인한다). 어느 쪽이든 응답은 같다.
 * 잠금 순서는 확인서 → 세션이다(V8 머리말의 잠금 순서와 같은 방향 — 무효·정정·만료가 확인서를 잠근 뒤 세션을 취소한다).
 */
final class SignSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();
    /** 토큰 경로의 세션을 모를 때의 행위자. */
    static final Actor ANONYMOUS = new Actor("anonymous", "CUSTOMER");
    static final String SESSION_TARGET = "SIGN_SESSION";

    /** 토큰으로 세션을 열 수 없는 사유(감사에만, 응답은 하나). */
    enum DenyReason {
        UNKNOWN_TOKEN,
        SESSION_CLOSED,
        SESSION_EXPIRED
    }

    sealed interface Access {
        record Granted(SignSession session, Loaded loaded) implements Access {
        }

        record Denied(DenyReason reason, DisclosureId disclosureOrNull, UUID sessionOrNull) implements Access {
        }
    }

    private final SignSessionStore sessions;
    private final DisclosureLoader loader;
    private final AgentDirectory agents;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final TenantProfilePort tenants;

    SignSupport(SignSessionStore sessions, DisclosureLoader loader, AgentDirectory agents, AuditPort audit, WorkflowTransactions transactions,
                Clock clock, TenantProfilePort tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 원문 토큰 해석. 형식이 틀리면(테넌트 형식 포함) 감사 없이 거부 — 테넌트를 모른다. */
    static SignToken parse(String rawToken) {
        return SignToken.parse(rawToken);
    }

    /** 고객 토큰 경로의 감사 행위자: 세션에 귀속된 익명 고객. */
    static Actor customer(SignSession s) {
        return new Actor(SESSION_TARGET + ":" + s.sessionId(), "CUSTOMER");
    }

    /** 토큰 경로의 호출자(세션을 열기 전): 토큰 접두의 테넌트, 익명 고객. */
    static Caller anonymous(SignToken token) {
        return new Caller(token.tenant(), ANONYMOUS.subject(), Channel.SIGN_TOKEN);
    }

    /** 토큰이 연 세션의 고객 호출자 — 인가 포트는 세션 존재만 본다(토큰이 곧 자격, 6A 계획 §3.2). */
    static Caller customerCaller(SignToken token, SignSession s) {
        return new Caller(token.tenant(), customer(s).subject(), Channel.SIGN_TOKEN);
    }

    /** 현장 기기 토큰을 설계사가 넘길 때: 토큰의 테넌트가 호출자의 테넌트여야 한다 — 아니면 토큰 거부와 같다(존재 누설 없음). */
    static void sameTenant(Caller caller, SignToken token) {
        if (!caller.tenant().equals(token.tenant())) {
            throw new SignTokenRejected();
        }
    }

    /** 바인딩된 트랜잭션 안에서: 토큰 → 세션(잠금, 확인서 먼저) → OPEN·TTL. */
    Access access(TenantId tenant, SignToken token, Instant now) {
        Optional<SignSession> found = sessions.findByToken(token.hash());
        if (found.isEmpty()) {
            return new Access.Denied(DenyReason.UNKNOWN_TOKEN, null, null);
        }
        Loaded l = loader.load(tenant, found.get().disclosureId());
        SignSession s = sessions.lock(found.get().sessionId()).orElseThrow();
        if (s.state().status() != SessionStatus.OPEN) {
            return new Access.Denied(DenyReason.SESSION_CLOSED, s.disclosureId(), s.sessionId());
        }
        if (SessionWindow.elapsed(now, s.expiresAt())) {
            return new Access.Denied(DenyReason.SESSION_EXPIRED, s.disclosureId(), s.sessionId());
        }
        return new Access.Granted(s, l);
    }

    /** 트랜잭션 밖에서: 거부 사실을 남기고(실패해도 무시) 단일 거부 예외를 돌려준다. */
    SignTokenRejected deny(TenantId tenant, String operation, Access.Denied denied) {
        try {
            transactions.inTenant(tenant, () -> {
                tenants.profile(tenant);                                  // 없는 테넌트면 예외 — 아무것도 남기지 않는다
                ObjectNode detail = JSON.createObjectNode().put("operation", operation).put("reason", denied.reason().name());
                if (denied.sessionOrNull() != null) {
                    detail.put("sessionId", denied.sessionOrNull().toString());
                }
                boolean known = denied.disclosureOrNull() != null;
                return audit.append(new AuditEntry(clock.instant(), ANONYMOUS.subject(), ANONYMOUS.role(), AuditAction.SIGN_SESSION_DENIED,
                        known ? CommandRunner.TARGET : SESSION_TARGET, known ? denied.disclosureOrNull().toString() : null, detail));
            });
        } catch (RuntimeException ignored) {
            // 없는 테넌트 등 — 응답은 같아야 한다(존재 누설 금지, 승인 B2; 같은 지연은 Phase 6)
        }
        return new SignTokenRejected();
    }

    /** 행위자가 이 확인서의 담당 설계사인가({@code identity_link} 해석 agent_id = 확인서 agent_id, AGENT 역할). */
    boolean assigned(Actor actor, Disclosure d) {
        return agents.find(actor.subject()).filter(l -> l.hasRole("AGENT")).flatMap(AgentDirectory.LinkedIdentity::agentId)
                .map(id -> id.value().equals(d.agentId())).orElse(false);
    }

    /** 이미 서명한 역할인가. */
    static boolean signed(Disclosure d, SignerRole role) {
        return d.signatures().stream().anyMatch(m -> m.role() == role);
    }

    /** SEQUENTIAL에서 서명자 집합의 앞 역할 중 서명하지 않은 것이 있는가(집합 밖 역할은 순서가 없다 — OPTIONAL 관리자). */
    static boolean outOfOrder(EffectiveRule rule, Disclosure d, SignerRole role) {
        if (rule.signOrder() != SignOrder.SEQUENTIAL) {
            return false;
        }
        List<SignerRole> set = rule.signerSet();
        int at = set.indexOf(role);
        return at > 0 && set.subList(0, at).stream().anyMatch(r -> !signed(d, r));
    }

    /** 고객 채널이 룰에 있고 켜져 있는가. */
    static boolean channelEnabled(EffectiveRule rule, SignatureChannel channel) {
        return rule.channels().containsKey(channel) && rule.channels().get(channel).enabled();
    }

    /** 그 역할이 서명할 수 있는가: 서명자 집합 또는 OPTIONAL 관리자 확인(GD104와 같은 규칙). */
    static boolean inSignerSet(EffectiveRule rule, SignerRole role) {
        return rule.signerSet().contains(role)
                || (role == SignerRole.MANAGER && rule.managerConfirmMode() == com.ga.disclosure.domain.enums.ManagerConfirmMode.OPTIONAL);
    }

    /** 업무 거부 감사 1행(코드 전부). */
    void reject(Actor actor, Disclosure d, String command, List<SignRejection> rejections, ObjectNode extraOrNull) {
        ObjectNode detail = JSON.createObjectNode().put("command", command).put("status", d.status().name());
        ArrayNode codes = detail.putArray("rejections");
        rejections.forEach(r -> codes.add(r.name()));
        if (extraOrNull != null) {
            detail.setAll(extraOrNull);
        }
        record(actor, AuditAction.DISCLOSURE_REJECT, d.id(), detail);
    }

    void record(Actor actor, AuditAction action, DisclosureId id, ObjectNode detail) {
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), action, CommandRunner.TARGET, id.toString(), detail));
    }

    /**
     * 서명 시각: 확인서마다 엄격히 증가한다 — max(시계(µs, DB 정밀도), 직전 서명 + 1µs). 서명 목록의 순서가 곧 서명 순서여야 SEQUENTIAL 판정·증거 패키지의
     * 순번이 재현되는데, 같은 시각 두 서명은 저장 뒤 순서가 정해지지 않고 여러 인스턴스의 시계 차이는 뒤 서명을 앞 시각으로 만들 수 있다(확인서 행 잠금이
     * 서명을 직렬화하므로 이 시각이 커밋 순서다).
     */
    static Instant signingTime(Disclosure d, Instant clockNow) {
        Instant now = clockNow.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (d.signatures().isEmpty()) {
            return now;
        }
        Instant floor = d.signatures().getLast().signedAt().plus(1, java.time.temporal.ChronoUnit.MICROS);
        return now.isBefore(floor) ? floor : now;
    }

    /** 서명 마크(역할·시각) — 애그리게이트 목록용. */
    static ValidationSubject.SignatureMark mark(SignerRole role, Instant at) {
        return new ValidationSubject.SignatureMark(role, at);
    }
}
