package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.disclosure.DisclosureCommand;
import com.ga.disclosure.domain.disclosure.DisclosureStateTable;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.sign.retention.SignDeadline;
import com.ga.disclosure.sign.session.SessionEvent;
import com.ga.disclosure.sign.session.SessionStatus;
import com.ga.disclosure.sign.session.SessionWindow;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.disclosure.DisclosureLoader.Loaded;
import com.ga.disclosure.workflow.sign.SignSession;
import com.ga.disclosure.workflow.sign.SignSessionStore;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 만료 배치(설계서 §6.5, 4 계획 §7.4 — 스케줄 등록은 Phase 6, 지금은 CLI {@code disclosure expire}):
 * <ol>
 *   <li>서명을 기다리는 확인서(SEALED·PARTIALLY_SIGNED)마다 트랜잭션 1개: 다시 잠가 판정 시각 {@code asOf}가 서명 기한 끝(봉인일 KST + 고정 룰
 *       {@code signDeadlineDays}의 23:59:59.999999 KST)을 지났으면 EXPIRED, OPEN 세션 전부 REVOKED(DOCUMENT_EXPIRED), 플래그 {@code SIGN_EXPIRED},
 *       종이 스캔 검토는 문서 상태로 닫힘, 감사 {@code DISCLOSURE_EXPIRE}. 받은 서명은 남는다.</li>
 *   <li>그다음 TTL이 지난 OPEN 세션을 EXPIRED로 기록한다(접근 때는 상태를 바꾸지 않고 거부만 한다 — 4 계획 §2.3). 세션마다 트랜잭션 1개, 확인서 → 세션
 *       순으로 잠근다.</li>
 * </ol>
 * 다시 돌리면 바뀌는 것이 없다(NOOP). {@code asOf}는 운영자가 정하는 판정 시각이며(기본 = 시계) 감사에 실제 시각과 함께 남는다.
 */
public final class ExpireService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 실행 결과. */
    public record Report(List<DisclosureId> expired, int stillOpen, int sessionsExpired) {
        public Report {
            expired = List.copyOf(expired);
        }
    }

    private final DisclosureStore store;
    private final DisclosureFlagPort flags;
    private final SignSessionStore sessions;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final CommandRunner runner;
    private final DisclosureLoader loader;
    private final SessionClosing closing;

    public ExpireService(DisclosureServiceDeps deps, SignSessionStore sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.store = deps.store();
        this.flags = deps.flags();
        this.audit = deps.audit();
        this.transactions = deps.transactions();
        this.clock = deps.clock();
        this.runner = new CommandRunner(transactions, audit, clock);
        this.loader = deps.loader();
        this.closing = new SessionClosing(sessions, audit, clock);
    }

    public Report run(TenantId tenant, Actor actor, Instant asOf, int limit) {
        Objects.requireNonNull(asOf, "asOf");
        List<DisclosureId> candidates = transactions.inTenant(tenant, () -> store.awaitingSignatures(limit));
        List<DisclosureId> expired = new ArrayList<>();
        int stillOpen = 0;
        for (DisclosureId id : candidates) {
            boolean done = runner.inTransaction(tenant, actor, DisclosureCommand.EXPIRE.name(), id.toString(), () -> expireOne(tenant, actor, id, asOf));
            if (done) {
                expired.add(id);
            } else {
                stillOpen++;
            }
        }
        int sessionsExpired = 0;
        for (SignSession s : transactions.inTenant(tenant, () -> sessions.openElapsed(asOf, limit))) {
            boolean done = runner.inTransaction(tenant, actor, "SIGN_SESSION_EXPIRE", s.disclosureId().toString(), () -> {
                loader.load(tenant, s.disclosureId());                                   // 확인서 먼저 잠근다
                SignSession locked = sessions.lock(s.sessionId()).orElseThrow();
                if (locked.state().status() != SessionStatus.OPEN || !SessionWindow.elapsed(asOf, locked.expiresAt())) {
                    return false;
                }
                closing.closeOne(actor, locked, null, asOf);
                return true;
            });
            if (done) {
                sessionsExpired++;
            }
        }
        return new Report(expired, stillOpen, sessionsExpired);
    }

    private boolean expireOne(TenantId tenant, Actor actor, DisclosureId id, Instant asOf) {
        Loaded l = loader.load(tenant, id);
        Disclosure d = l.disclosure();
        if (!DisclosureStateTable.allows(d.status(), DisclosureCommand.EXPIRE) || !d.deadline().orElseThrow().passed(asOf)) {
            return false;                                                                // 그 사이 서명·완료·무효됐거나 기한 전
        }
        DisclosureStatus from = d.status();
        SignDeadline deadline = d.deadline().orElseThrow();
        d.expire(asOf);
        store.save(d);
        Instant now = clock.instant();
        List<UUID> closed = closing.closeOpen(actor, id, SessionEvent.DOCUMENT_EXPIRE, asOf);
        DisclosureFlagPort.RaisedFlag flag = flags.raise(DisclosureFlagPort.Type.SIGN_EXPIRED, "MEDIUM", id, CommandRunner.TARGET, id.toString(), now);
        record(actor, AuditAction.FLAG_RAISE, id, JSON.createObjectNode().put("flagId", flag.flagId().toString())
                .put("type", DisclosureFlagPort.Type.SIGN_EXPIRED.name()).put("created", flag.created()));
        for (DisclosureFlagPort.OpenFlag f : flags.openFor(id)) {
            if (LifecycleService.CLOSED_BY_DOCUMENT_STATE.contains(f.type())
                    && flags.resolve(f.flagId(), DisclosureFlagPort.Resolution.SUPERSEDED_BY_DOCUMENT_STATE, actor.subject(), now)) {
                record(actor, AuditAction.FLAG_RESOLVE, id, JSON.createObjectNode().put("flagId", f.flagId().toString()).put("type", f.type().name())
                        .put("resolution", DisclosureFlagPort.Resolution.SUPERSEDED_BY_DOCUMENT_STATE.name()).put("resolvedBy", actor.subject()));
            }
        }
        ObjectNode detail = JSON.createObjectNode().put("from", from.name()).put("to", d.status().name())
                .put("deadline", deadline.lastDay().toString()).put("asOf", asOf.toString()).put("signatures", d.signatures().size());
        ArrayNode sessionIds = detail.putArray("closedSessions");
        closed.forEach(c -> sessionIds.add(c.toString()));
        record(actor, AuditAction.DISCLOSURE_EXPIRE, id, detail);
        return true;
    }

    private void record(Actor actor, AuditAction action, DisclosureId id, ObjectNode detail) {
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), action, CommandRunner.TARGET, id.toString(), detail));
    }
}
