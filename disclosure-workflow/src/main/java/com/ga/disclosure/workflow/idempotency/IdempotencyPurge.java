package com.ga.disclosure.workflow.idempotency;

import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** 만료된 Idempotency-Key 정리(작업 IDEMPOTENCY_PURGE, 6A 승인 Q12). 만료 전 행은 GD120이 지우지 못하게 한다. */
public final class IdempotencyPurge {

    private final IdempotencyStore store;
    private final AuthorizationPort authz;
    private final WorkflowTransactions transactions;
    private final Clock clock;

    public IdempotencyPurge(IdempotencyStore store, AuthorizationPort authz, WorkflowTransactions transactions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record Report(Instant asOf, int purged) {
    }

    @UseCaseEntry(Action.IDEMPOTENCY_PURGE)
    public Report run(Caller caller, int limit) {
        Objects.requireNonNull(caller, "caller");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return transactions.inTenant(caller.tenant(), () -> {
            authz.require(caller, Action.IDEMPOTENCY_PURGE, Target.none());
            Instant asOf = clock.instant();
            return new Report(asOf, store.purgeExpired(asOf, limit));
        });
    }
}
