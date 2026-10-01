package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.disclosure.IllegalTransition;
import com.ga.disclosure.rules.resolve.RuleResolutionException;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 감사 실패 기록 규약(3A 계획 §4, 승인 B2 — 설계서 §6.2 표).
 * <ul>
 *   <li>업무 거부(검증 차단·엔진 응답 거부·노후): 업무 트랜잭션이 정상 커밋하고 감사 행이 남는다 — 이 클래스가 아니라 유스케이스가 기록한다.</li>
 *   <li>명령 오류(예외): 업무 트랜잭션은 롤백되고, 그 뒤 <b>같은 테넌트</b>의 별도 트랜잭션으로 {@code COMMAND_FAILED} 1행(명령·대상·예외 종류·
 *       오류 코드)을 남긴다. 메시지는 넣지 않는다(입력값이 섞일 수 있다, 절대 규칙 6). 실패 기록이 실패하면 원 예외에 suppressed로 붙이고
 *       ERROR 로그를 남긴다.</li>
 *   <li>테넌트 미바인딩(테넌트 없이 호출): 감사 대상이 아니다 — 실패 기록을 시도하지 않고 원 예외만 전파한다(401·기동 오류로 매핑).</li>
 * </ul>
 */
final class CommandRunner {

    private static final System.Logger LOG = System.getLogger(CommandRunner.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TARGET = "DISCLOSURE";

    private final WorkflowTransactions transactions;
    private final AuditPort audit;
    private final Clock clock;

    CommandRunner(WorkflowTransactions transactions, AuditPort audit, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 업무 트랜잭션 하나. 예외면 롤백 뒤 {@code COMMAND_FAILED}를 남기고 다시 던진다. */
    <T> T inTransaction(TenantId tenant, Actor actor, String command, String targetIdOrNull, Supplier<T> work) {
        if (tenant == null) {
            throw new IllegalArgumentException("tenant is not bound — " + command + " is not audited (unauthenticated or boot error)");
        }
        Objects.requireNonNull(actor, "actor");
        try {
            return transactions.inTenant(tenant, work);
        } catch (RuntimeException e) {
            recordFailure(tenant, actor, command, targetIdOrNull, e);
            throw e;
        }
    }

    /** 제한 시간이 있는 업무 트랜잭션 하나(봉인). 예외면 롤백 뒤 {@code COMMAND_FAILED}. */
    <T> T inTransaction(TenantId tenant, Actor actor, String command, String targetIdOrNull, java.time.Duration timeout, Supplier<T> work) {
        if (tenant == null) {
            throw new IllegalArgumentException("tenant is not bound — " + command + " is not audited (unauthenticated or boot error)");
        }
        Objects.requireNonNull(actor, "actor");
        try {
            return transactions.inTenant(tenant, timeout, work);
        } catch (RuntimeException e) {
            recordFailure(tenant, actor, command, targetIdOrNull, e);
            throw e;
        }
    }

    /** 트랜잭션 밖에서 난 명령 오류(엔진 호출 등)의 실패 사실을 남기고 원 예외를 돌려준다(호출자가 던진다). */
    RuntimeException failed(TenantId tenant, Actor actor, String command, String targetIdOrNull, RuntimeException failure) {
        recordFailure(tenant, actor, command, targetIdOrNull, failure);
        return failure;
    }

    private void recordFailure(TenantId tenant, Actor actor, String command, String targetIdOrNull, RuntimeException failure) {
        ObjectNode detail = JSON.createObjectNode()
                .put("command", command)
                .put("exception", failure.getClass().getSimpleName())
                .put("code", codeOf(failure));
        try {
            transactions.inTenant(tenant, () -> audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(),
                    AuditAction.COMMAND_FAILED, TARGET, targetIdOrNull, detail)));
        } catch (RuntimeException recordFailure) {
            failure.addSuppressed(recordFailure);
            LOG.log(System.Logger.Level.ERROR, "COMMAND_FAILED could not be recorded for " + command + " (" + codeOf(failure) + ")");
        }
    }

    static String codeOf(RuntimeException e) {
        return switch (e) {
            case IllegalTransition t -> "ILLEGAL_TRANSITION";
            case RuleResolutionException r -> "RULE_" + r.failure().name();
            case EngineUnavailableException u -> u.code();
            case CommandRejectedException c -> c.code();
            case DisclosureNotFoundException n -> "NOT_FOUND";
            case IllegalArgumentException i -> "INVALID_INPUT";
            default -> "UNEXPECTED";
        };
    }
}
