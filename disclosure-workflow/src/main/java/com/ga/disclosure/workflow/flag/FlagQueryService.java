package com.ga.disclosure.workflow.flag;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.ListGrant;
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
 * 준법 플래그 조회(6A 수용심사 §2 ①, {@code FLAG_READ}): 관리자는 조직 아래, 준법은 테넌트 전체, 설계사는 칸이 없다 — 의심받는 설계사가 대리 서명 플래그를
 * 보지 않는다. 범위 밖·없는 확인서는 같은 인가 거부(404). 개인정보를 읽지 않는다(플래그·확인서 번호뿐).
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
            authz.require(caller, Action.FLAG_READ, Target.disclosure(id));
            return flags.forDisclosure(id);
        });
    }

    /** 범위로 걸러진 플래그 목록 한 쪽(1..{@value #MAX_PAGE}). */
    @UseCaseEntry(Action.FLAG_READ)
    public Page<FlagLookup.Listed> list(Caller caller, int limit, Optional<String> after, Optional<FlagLookup.FlagStatus> status, Optional<String> type) {
        Objects.requireNonNull(caller, "caller");
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_PAGE);
        }
        Optional<FlagLookup.Position> from = after.map(c -> position(cursors.open(caller.tenant(), STREAM, c)));
        return transactions.inTenant(caller.tenant(), () -> {
            ListGrant grant = authz.requireList(caller, Action.FLAG_READ);
            List<FlagLookup.Listed> rows = flags.page(grant.scope(), status, type, from, limit + 1);
            if (rows.size() <= limit) {
                return new Page<>(rows, Optional.empty());
            }
            FlagLookup.Listed last = rows.get(limit - 1);
            return new Page<>(rows.subList(0, limit), Optional.of(cursors.seal(caller.tenant(), STREAM, last.raisedAt() + "|" + last.flagId())));
        });
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
