package com.ga.disclosure.workflow.feed;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.RejectionCategory;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.disclosure.CommandRejectedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 이벤트 피드(6A 계획 §4.1, 설계서 §4.5, 승인 Q5 — 포털 설계서 §4.1 규약). 서비스 주체 {@code FEED_CONSUMER}만(사람 역할은 404).
 * <ul>
 *   <li>읽기: {@code afterSeq} 다음부터 seq 오름차순·갭 없음, 최대 {@value #MAX_LIMIT}건. {@code afterSeq}를 생략하면 <b>ack한 지점</b>부터 — ack 전
 *       재요청은 같은 이벤트를 다시 준다(at-least-once). {@code nextSeq} = 돌려준 마지막 seq(없으면 시작점), {@code headSeq} = 테넌트의 마지막 seq.
 *       시작점이 머리보다 크면 {@code AFTER_BEYOND_HEAD}(소비자가 다른 발행자 — 예: 복원된 DB — 를 보고 있다). 상태를 바꾸지 않으며 감사하지 않는다.</li>
 *   <li>ack: {@code upToSeq} 이하의 아직 발행 기록이 없는 행에 발행 시각을 한 번 기록한다(GD106). 이미 ack한 지점 이하는 아무것도 하지 않는다. 머리보다
 *       크면 {@code ACK_BEYOND_HEAD}. 같은 트랜잭션에서 감사 {@code EVENT_FEED_ACK}(요청 seq·표시한 행 수·ack 지점).</li>
 * </ul>
 */
public final class EventFeed {

    public static final int MAX_LIMIT = 1000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 피드 거부(전부 요청 거부 — 422). */
    public enum Rejection implements RejectionCategory.Categorized {
        AFTER_BEYOND_HEAD,
        ACK_BEYOND_HEAD;

        @Override
        public RejectionCategory category() {
            return RejectionCategory.INVALID;
        }
    }

    /** 한 번의 읽기. */
    public record FeedPage(List<JsonNode> events, long nextSeq, long headSeq) {
        public FeedPage {
            events = List.copyOf(events);
        }
    }

    /** ack 결과: 이제 ack한 지점과 머리. */
    public record AckResult(long ackedSeq, long headSeq) {
    }

    private final EventFeedStore store;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final AuthorizationPort authz;

    public EventFeed(EventFeedStore store, AuditPort audit, WorkflowTransactions transactions, Clock clock, AuthorizationPort authz) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authz = Objects.requireNonNull(authz, "authz");
    }

    @UseCaseEntry(Action.EVENT_FEED_READ)
    public FeedPage read(Caller caller, OptionalLong afterSeq, int limit) {
        Objects.requireNonNull(caller, "caller");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT);
        }
        if (afterSeq.isPresent() && afterSeq.getAsLong() < 0) {
            throw new IllegalArgumentException("afterSeq must be >= 0");
        }
        return transactions.inTenant(caller.tenant(), () -> {
            authz.require(caller, Action.EVENT_FEED_READ, Target.none());
            long head = store.head();
            long start = afterSeq.isPresent() ? afterSeq.getAsLong() : store.acknowledged();
            if (start > head) {
                throw reject(Rejection.AFTER_BEYOND_HEAD);
            }
            List<JsonNode> events = store.after(start, limit);
            long next = events.isEmpty() ? start : events.getLast().get("seq").asLong();
            return new FeedPage(events, next, head);
        });
    }

    @UseCaseEntry(Action.EVENT_FEED_ACK)
    public AckResult ack(Caller caller, long upToSeq) {
        Objects.requireNonNull(caller, "caller");
        if (upToSeq < 0) {
            throw new IllegalArgumentException("upToSeq must be >= 0");
        }
        return transactions.inTenant(caller.tenant(), () -> {
            Actor actor = authz.require(caller, Action.EVENT_FEED_ACK, Target.none());
            long head = store.head();
            if (upToSeq > head) {
                throw reject(Rejection.ACK_BEYOND_HEAD);
            }
            int marked = store.markPublished(upToSeq, clock.instant());
            long acked = store.acknowledged();
            audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.EVENT_FEED_ACK, null, null,
                    JSON.createObjectNode().put("upToSeq", upToSeq).put("marked", marked).put("ackedSeq", acked)));
            return new AckResult(acked, head);
        });
    }

    private static CommandRejectedException reject(Rejection r) {
        return new CommandRejectedException(r, "event feed request rejected: " + r.name());
    }
}
