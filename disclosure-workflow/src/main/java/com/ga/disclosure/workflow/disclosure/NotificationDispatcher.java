package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.NotifyRetryRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.sign.session.SessionStatus;
import com.ga.disclosure.sign.token.SignToken;
import com.ga.disclosure.sign.token.TokenSource;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.customer.Customer;
import com.ga.disclosure.workflow.customer.CustomerRefService;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.customer.NotificationPurpose;
import com.ga.disclosure.workflow.sign.NotificationStore;
import com.ga.disclosure.workflow.sign.NotifyFailure;
import com.ga.disclosure.workflow.sign.NotifyPort;
import com.ga.disclosure.workflow.sign.SignLink;
import com.ga.disclosure.workflow.sign.SignSession;
import com.ga.disclosure.workflow.sign.SignSessionStore;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 서명 링크 통지 디스패처(6A 계획 §7.2, 설계서 §6.5, 작업 종류 NOTIFY). 기한이 된 PENDING 행마다 <b>한 트랜잭션</b>:
 * <ol>
 *   <li>행을 잠근다({@code SKIP LOCKED} — 다른 디스패처가 쥔 행은 건너뛴다). 세션을 잠근다. 세션이 OPEN이 아니면 {@code CANCELLED(SESSION_CLOSED)},
 *       만료가 지났으면 {@code CANCELLED(SESSION_EXPIRED)}.</li>
 *   <li>고객 번호가 없으면 {@code DEAD(NO_PHONE)} + 플래그 {@code NOTIFY_FAILED} — 재시도해도 생기지 않는다.</li>
 *   <li>번호를 읽고(감사 {@code CUSTOMER_PHONE_READ}), 토큰을 만들어 세션에 해시·발송 시각을 1회 기록하고(GD123), 어댑터로 보낸다. 링크는
 *       {@code base + "#"…}(프래그먼트), 토큰 원문은 지역 변수와 어댑터 인자에만 있다. SENT + {@code SIGN_SESSION_SEND}.</li>
 * </ol>
 * 어댑터가 실패하면 그 트랜잭션은 롤백된다(토큰 해시도 남지 않는다). 별도 트랜잭션에서 시도 +1, 닫힌 오류 코드, 그리고 룰 {@code notify.retry}(실행 시점
 * 오늘 KST의 ACTIVE 룰)의 산식으로 다음 시도({@code NOTIFY_RETRY}) 또는 소진 {@code DEAD} + 플래그 {@code NOTIFY_FAILED}({@code NOTIFY_DEAD}).
 * 어댑터 성공 뒤 커밋이 실패하면 고객은 해시가 저장되지 않은 링크를 받는다 — 그 링크는 거부되고 다음 시도가 새 토큰으로 다시 보낸다(최소 1회 전달).
 */
public final class NotificationDispatcher {

    static final String TARGET = "NOTIFICATION";
    static final String ADAPTER_ERROR = "ADAPTER_ERROR";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NotificationStore outbox;
    private final SignSessionStore sessions;
    private final CustomerVault customers;
    private final CustomerRefService phones;
    private final DisclosureFlagPort flags;
    private final RuleResolver rules;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final TokenSource tokens;
    private final NotifyPort notify;
    private final String linkBase;
    private final AuthorizationPort authz;

    public NotificationDispatcher(DisclosureServiceDeps deps, NotificationStore outbox, SignSessionStore sessions, CustomerRefService phones,
                                  TokenSource tokens, NotifyPort notify, String linkBase) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.phones = Objects.requireNonNull(phones, "phones");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.notify = Objects.requireNonNull(notify, "notify");
        this.linkBase = Objects.requireNonNull(linkBase, "linkBase");
        if (!linkBase.endsWith("#")) {
            throw new IllegalArgumentException("ga.sign.link-base-url must end with '#' so the token stays in the fragment");
        }
        this.customers = deps.customers();
        this.flags = deps.flags();
        this.rules = deps.rules();
        this.audit = deps.audit();
        this.transactions = deps.transactions();
        this.clock = deps.clock();
        this.authz = deps.authz();
    }

    /** 실행 1회 요약(통지 ID만 — 번호·토큰 없음). */
    public record Report(TenantId tenant, Instant asOf, String ruleVersionId, List<UUID> sent, List<UUID> retried, List<UUID> dead,
                         List<UUID> cancelled, int skipped) {
        public Report {
            sent = List.copyOf(sent);
            retried = List.copyOf(retried);
            dead = List.copyOf(dead);
            cancelled = List.copyOf(cancelled);
        }

        public ObjectNode toJson() {
            ObjectNode o = JSON.createObjectNode().put("tenantId", tenant.value()).put("asOf", asOf.toString()).put("ruleVersionId", ruleVersionId)
                    .put("skipped", skipped);
            sent.forEach(id -> o.withArrayProperty("sent").add(id.toString()));
            retried.forEach(id -> o.withArrayProperty("retried").add(id.toString()));
            dead.forEach(id -> o.withArrayProperty("dead").add(id.toString()));
            cancelled.forEach(id -> o.withArrayProperty("cancelled").add(id.toString()));
            for (String k : List.of("sent", "retried", "dead", "cancelled")) {
                o.withArrayProperty(k);
            }
            return o;
        }
    }

    private enum Result {
        SENT, CANCELLED, DEAD, RETRIED, SKIPPED
    }

    /**
     * 어댑터 실패(코드만) — 이 예외만 재시도 대상이다. 다른 예외(DB 등)는 작업을 실패시킨다. 번호는 이미 읽었으므로 롤백된 {@code CUSTOMER_PHONE_READ}를 실패
     * 기록 트랜잭션이 다시 남긴다(읽은 사실이 감사에서 사라지지 않게).
     */
    private static final class SendFailed extends RuntimeException {
        final String code;
        final String keyId;

        SendFailed(String code, String keyId, RuntimeException cause) {
            super(code, cause);
            this.code = code;
            this.keyId = keyId;
        }
    }

    /** 기한이 된 통지를 {@code limit}건까지 보낸다. 판정 시각은 시계다. */
    @UseCaseEntry(Action.NOTIFY_DISPATCH)
    public Report run(Caller caller, int limit) {
        Objects.requireNonNull(caller, "caller");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        TenantId tenant = caller.tenant();
        Actor actor = transactions.inTenant(tenant, () -> authz.require(caller, Action.NOTIFY_DISPATCH, Target.none()));
        Instant asOf = clock.instant();
        EffectiveRule active = transactions.inTenant(tenant, () -> rules.resolve(tenant, LocalDate.ofInstant(asOf, SEOUL)));
        NotifyRetryRule retry = active.notifyRetry();
        String ruleVersionId = active.globalRuleVersionId().value();
        List<UUID> due = transactions.inTenant(tenant, () -> outbox.due(asOf, limit));
        List<UUID> sent = new ArrayList<>();
        List<UUID> retried = new ArrayList<>();
        List<UUID> dead = new ArrayList<>();
        List<UUID> cancelled = new ArrayList<>();
        int skipped = 0;
        for (UUID id : due) {
            Result r;
            try {
                r = transactions.inTenant(tenant, () -> attempt(tenant, actor, id, asOf, ruleVersionId));
            } catch (SendFailed failure) {
                r = transactions.inTenant(tenant, () -> recordFailure(actor, id, failure, retry, ruleVersionId));
                if (r == Result.SKIPPED) {
                    // 행이 PENDING이 아니다 — 실패한 시도가 아무것도 남기지 않았다(트랜잭션 롤백)
                    skipped++;
                    continue;
                }
                (r == Result.DEAD ? dead : retried).add(id);
                continue;
            }
            switch (r) {
                case SENT -> sent.add(id);
                case CANCELLED -> cancelled.add(id);
                case DEAD -> dead.add(id);
                case RETRIED -> retried.add(id);
                case SKIPPED -> skipped++;
            }
        }
        return new Report(tenant, asOf, ruleVersionId, sent, retried, dead, cancelled, skipped);
    }

    private Result attempt(TenantId tenant, Actor actor, UUID id, Instant asOf, String ruleVersionId) {
        Optional<NotificationStore.Pending> locked = outbox.lockDue(id, asOf);
        if (locked.isEmpty()) {
            return Result.SKIPPED;
        }
        NotificationStore.Pending p = locked.get();
        Instant now = clock.instant();
        SignSession s = sessions.lock(p.sessionId()).orElseThrow();
        if (s.state().status() != SessionStatus.OPEN || s.sentAt().isPresent()) {
            return cancel(actor, p, s, "SESSION_CLOSED", now);
        }
        if (!now.isBefore(s.expiresAt())) {
            return cancel(actor, p, s, "SESSION_EXPIRED", now);
        }
        Optional<Customer> customer = customers.find(p.recipient());
        if (customer.flatMap(Customer::phone).isEmpty()) {
            outbox.close(id, NotificationStore.Closed.DEAD, "NO_PHONE", false, now);
            deadFlag(actor, p, s, "NO_PHONE", p.attempts(), ruleVersionId, now);
            return Result.DEAD;
        }
        Sensitive<PhoneNumber> phone = phones.phoneForNotification(tenant, actor, p.recipient(), NotificationPurpose.REMOTE_LINK,
                p.sessionId().toString());
        SignToken token = SignToken.issue(tenant, tokens);
        sessions.update(s.sent(now, token.hash()));
        try {
            notify.sendSignLink(phone, SignLink.of(linkBase, token));      // 실패면 이 트랜잭션 전체가 롤백된다(토큰 해시 포함)
        } catch (NotifyFailure f) {
            throw new SendFailed(f.code(), customer.get().keyId(), f);
        } catch (RuntimeException e) {
            throw new SendFailed(ADAPTER_ERROR, customer.get().keyId(), e);
        }
        outbox.markSent(id, now);
        audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.SIGN_SESSION_SEND, CommandRunner.TARGET,
                s.disclosureId().toString(), JSON.createObjectNode().put("sessionId", s.sessionId().toString()).put("sent", true)
                .put("sentAt", now.toString()).put("notificationId", id.toString()).put("attempt", p.attempts() + 1)));
        return Result.SENT;
    }

    private Result cancel(Actor actor, NotificationStore.Pending p, SignSession s, String code, Instant now) {
        outbox.close(p.notificationId(), NotificationStore.Closed.CANCELLED, code, false, now);
        audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.NOTIFY_CANCELLED, TARGET, p.notificationId().toString(),
                JSON.createObjectNode().put("sessionId", s.sessionId().toString()).put("code", code)));
        return Result.CANCELLED;
    }

    private Result recordFailure(Actor actor, UUID id, SendFailed failure, NotifyRetryRule retry, String ruleVersionId) {
        Optional<NotificationStore.Pending> locked = outbox.lockPending(id);
        if (locked.isEmpty()) {
            return Result.SKIPPED;
        }
        NotificationStore.Pending p = locked.get();
        Instant now = clock.instant();
        String code = failure.code;
        audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.CUSTOMER_PHONE_READ, SealService.CUSTOMER_TARGET,
                p.recipient().value(), JSON.createObjectNode().put("purpose", NotificationPurpose.REMOTE_LINK.name())
                .put("reference", p.sessionId().toString()).put("keyId", failure.keyId).put("sendFailed", code)));
        int attempts = p.attempts() + 1;
        if (retry.exhaustedAfter(attempts)) {
            outbox.close(id, NotificationStore.Closed.DEAD, code, true, now);
            SignSession s = sessions.lock(p.sessionId()).orElseThrow();
            deadFlag(actor, p, s, code, attempts, ruleVersionId, now);
            return Result.DEAD;
        }
        Instant base = now.isAfter(p.nextAttemptAt()) ? now : p.nextAttemptAt();
        Instant next = base.plusSeconds(retry.delaySeconds(attempts));
        outbox.recordFailure(id, next, code);
        audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.NOTIFY_RETRY, TARGET, id.toString(), JSON.createObjectNode()
                .put("sessionId", p.sessionId().toString()).put("code", code).put("attempts", attempts).put("nextAttemptAt", next.toString())
                .put("ruleVersionId", ruleVersionId)));
        return Result.RETRIED;
    }

    private void deadFlag(Actor actor, NotificationStore.Pending p, SignSession s, String code, int attempts, String ruleVersionId, Instant now) {
        DisclosureFlagPort.RaisedFlag flag = flags.raise(DisclosureFlagPort.Type.NOTIFY_FAILED, "HIGH", s.disclosureId(), "SIGN_SESSION",
                s.sessionId().toString(), now);
        audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.NOTIFY_DEAD, TARGET, p.notificationId().toString(),
                JSON.createObjectNode().put("sessionId", s.sessionId().toString()).put("code", code).put("attempts", attempts)
                .put("ruleVersionId", ruleVersionId).put("flagId", flag.flagId().toString())));
    }
}
