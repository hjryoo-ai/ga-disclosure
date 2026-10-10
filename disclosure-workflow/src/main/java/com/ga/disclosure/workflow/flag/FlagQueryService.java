package com.ga.disclosure.workflow.flag;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.ListGrant;
import com.ga.disclosure.workflow.authz.Role;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.page.CursorPort;
import com.ga.disclosure.workflow.page.InvalidCursorException;
import com.ga.disclosure.workflow.page.Page;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 준법 플래그 조회(6A 수용심사 §2 ①, {@code FLAG_READ}): 관리자는 조직 아래, 준법은 테넌트 전체. 설계사는 자기 확인서(OWN)의 플래그 중
 * <b>열릴 때 설계사 가시로 고정된 것만</b>(Phase 7 승인 Q1 — 룰 기본은 11유형 전부 비가시라 빈 목록이고, 의심받는 설계사가 대리 서명 플래그를 보지 않는다).
 * 가시성은 허가를 준 역할로 이 유스케이스가 정한다 — 화면은 거르지 않는다. 범위 밖·없는 확인서는 같은 인가 거부(404). 개인정보를 읽지 않는다(플래그·확인서 번호뿐).
 */
public final class FlagQueryService {

    public static final int MAX_PAGE = 100;
    static final String STREAM = "flags";

    private final FlagLookup flags;
    private final WorkflowTransactions transactions;
    private final AuthorizationPort authz;
    private final CursorPort cursors;

    public FlagQueryService(FlagLookup flags, WorkflowTransactions transactions, AuthorizationPort authz, CursorPort cursors) {
        this.flags = Objects.requireNonNull(flags, "flags");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /** 확인서 하나의 플래그(열림·닫힘). */
    @UseCaseEntry(Action.FLAG_READ)
    public List<FlagLookup.Listed> forDisclosure(Caller caller, DisclosureId id) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(id, "id");
        return transactions.inTenant(caller.tenant(), () -> {
            Actor actor = authz.require(caller, Action.FLAG_READ, Target.disclosure(id));
            return flags.forDisclosure(id, audience(actor));
        });
    }

    /** 범위로 걸러진 플래그 목록 한 쪽(1..{@value #MAX_PAGE}). */
    @UseCaseEntry(Action.FLAG_READ)
    public Page<FlagLookup.Listed> list(Caller caller, int limit, Optional<String> after, FlagLookup.Filter filter) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(filter, "filter");
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_PAGE);
        }
        Optional<FlagLookup.Position> from = after.map(c -> position(cursors.open(caller.tenant(), STREAM, c)));
        return transactions.inTenant(caller.tenant(), () -> {
            ListGrant grant = authz.requireList(caller, Action.FLAG_READ);
            List<FlagLookup.Listed> rows = flags.page(grant.scope(), filter, from, limit + 1, audience(grant.actor()));
            if (rows.size() <= limit) {
                return new Page<>(rows, Optional.empty());
            }
            FlagLookup.Listed last = rows.get(limit - 1);
            return new Page<>(rows.subList(0, limit), Optional.of(cursors.seal(caller.tenant(), STREAM, last.raisedAt() + "|" + last.flagId())));
        });
    }

    /** 허가를 준 역할이 설계사면 설계사 가시 행만. 주체가 관리자·준법 역할도 가지면 그 역할이 먼저 허가한다({@code Role} 선언 순서). */
    static FlagLookup.Audience audience(Actor actor) {
        return Role.AGENT.name().equals(actor.role()) ? FlagLookup.Audience.AGENT : FlagLookup.Audience.STAFF;
    }

    private static FlagLookup.Position position(String q) {
        int bar = q.indexOf('|');
        try {
            return new FlagLookup.Position(Instant.parse(q.substring(0, bar)), UUID.fromString(q.substring(bar + 1)));
        } catch (RuntimeException e) {
            throw new InvalidCursorException();
        }
    }
}
