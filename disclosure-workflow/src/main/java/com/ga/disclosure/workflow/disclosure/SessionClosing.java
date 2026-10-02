package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.sign.session.SessionEvent;
import com.ga.disclosure.sign.session.SessionWindow;
import com.ga.disclosure.sign.session.SignSessionState;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.sign.SignSession;
import com.ga.disclosure.workflow.sign.SignSessionStore;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 확인서의 OPEN 세션 닫기(4 계획 §2.3·§7.4, 3B 수용심사 §3-5): 재발급·무효·정정·만료가 같은 트랜잭션에서 부른다(확인서 행을 잠근 뒤 — 잠금 순서 확인서 →
 * 세션). 문서 사건(무효·정정·만료)은 언제나 REVOKED(사건별 사유)다 — 세션 만료는 서명 기한 끝을 넘지 않으므로 만료 배치 때는 모든 OPEN 세션이 이미 TTL을
 * 지났고, 그래도 닫힌 원인은 문서다. 재발급만 TTL이 지난 세션을 그 사실대로 EXPIRED로 기록한다(4 계획 §2.3). 감사는 세션마다 1행(대상 = 확인서).
 */
final class SessionClosing {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SignSessionStore sessions;
    private final AuditPort audit;
    private final Clock clock;

    SessionClosing(SignSessionStore sessions, AuditPort audit, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 닫은 세션 ID. */
    List<UUID> closeOpen(Actor actor, DisclosureId id, SessionEvent cause, Instant now) {
        List<UUID> closed = new ArrayList<>();
        for (SignSession open : sessions.openFor(id)) {
            closeOne(actor, open, cause, now);
            closed.add(open.sessionId());
        }
        return closed;
    }

    /**
     * 세션 하나: {@code cause}가 없으면 TTL 경과 기록(EXPIRED, 경과하지 않았으면 호출자 오류), 재발급이면 경과한 세션은 EXPIRED, 그 밖에는
     * {@code cause}로 REVOKED.
     */
    void closeOne(Actor actor, SignSession open, SessionEvent causeOrNull, Instant now) {
        boolean ttlElapsed = SessionWindow.elapsed(now, open.expiresAt());
        if (!ttlElapsed && causeOrNull == null) {
            throw new IllegalArgumentException("session " + open.sessionId() + " has not elapsed");
        }
        boolean elapsed = causeOrNull == null || (causeOrNull == SessionEvent.REISSUE && ttlElapsed);
        SignSessionState next = elapsed ? open.state().elapse() : open.state().revoke(causeOrNull);
        sessions.update(open.with(next, now));
        ObjectNode detail = JSON.createObjectNode().put("sessionId", open.sessionId().toString());
        AuditAction action = elapsed ? AuditAction.SIGN_SESSION_EXPIRE : AuditAction.SIGN_SESSION_REVOKE;
        if (!elapsed) {
            detail.put("reason", next.revokeReason().name());
        }
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), action, CommandRunner.TARGET, open.disclosureId().toString(),
                detail));
    }
}
