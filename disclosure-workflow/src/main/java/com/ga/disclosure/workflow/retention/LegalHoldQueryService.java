package com.ga.disclosure.workflow.retention;

import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
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

/** 법적 보류 목록(6A 계획 §4.1, {@code LEGAL_HOLD_READ} — 준법). 서명된 커서. 보류 사유 텍스트는 API 층이 싣지 않는다(개인정보 컬럼). */
public final class LegalHoldQueryService {

    public static final int MAX_PAGE = 100;
    static final String STREAM = "legal-holds";

    private final LegalHoldStore holds;
    private final WorkflowTransactions transactions;
    private final AuthorizationPort authz;
    private final CursorPort cursors;

    public LegalHoldQueryService(LegalHoldStore holds, WorkflowTransactions transactions, AuthorizationPort authz, CursorPort cursors) {
        this.holds = Objects.requireNonNull(holds, "holds");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    @UseCaseEntry(Action.LEGAL_HOLD_READ)
    public Page<LegalHoldStore.Hold> list(Caller caller, int limit, Optional<String> after) {
        Objects.requireNonNull(caller, "caller");
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_PAGE);
        }
        Optional<LegalHoldStore.Position> from = after.map(c -> position(cursors.open(caller.tenant(), STREAM, c)));
        return transactions.inTenant(caller.tenant(), () -> {
            authz.require(caller, Action.LEGAL_HOLD_READ, Target.none());
            List<LegalHoldStore.Hold> rows = holds.page(from, limit + 1);
            if (rows.size() <= limit) {
                return new Page<>(rows, Optional.empty());
            }
            LegalHoldStore.Hold last = rows.get(limit - 1);
            return new Page<>(rows.subList(0, limit), Optional.of(cursors.seal(caller.tenant(), STREAM, last.placedAt() + "|" + last.holdId())));
        });
    }

    private static LegalHoldStore.Position position(String q) {
        int bar = q.indexOf('|');
        try {
            return new LegalHoldStore.Position(Instant.parse(q.substring(0, bar)), UUID.fromString(q.substring(bar + 1)));
        } catch (RuntimeException e) {
            throw new InvalidCursorException();
        }
    }
}
