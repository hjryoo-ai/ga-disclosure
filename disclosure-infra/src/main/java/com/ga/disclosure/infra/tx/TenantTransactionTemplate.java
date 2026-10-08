package com.ga.disclosure.infra.tx;

import com.ga.disclosure.compliance.rules.TenantTransactions;
import com.ga.disclosure.workflow.ConcurrentWriteConflict;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * {@link TenantTransactions}·{@link WorkflowTransactions} 구현: {@link TenantContext}에 테넌트를 바인딩하고 그 안에서 트랜잭션 하나를 연다.
 * 트랜잭션 매니저는 {@code TenantSessionBinder}이므로 시작 시 {@code app.tenant_id}가 설정되어 RLS가 적용된다.
 */
@Component
public class TenantTransactionTemplate implements TenantTransactions, WorkflowTransactions {

    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate template;

    public TenantTransactionTemplate(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        this.template = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T inTenant(TenantId tenant, Supplier<T> work) {
        return run(template, tenant, work);
    }

    /** 스프링 트랜잭션 제한 시간: JDBC 문장마다 남은 시간을 쿼리 제한으로 걸고, 넘기면 {@code TransactionTimedOutException}으로 롤백한다. */
    @Override
    public <T> T inTenant(TenantId tenant, java.time.Duration timeout, Supplier<T> work) {
        TransactionTemplate limited = new TransactionTemplate(transactionManager);
        limited.setTimeout(Math.toIntExact(Math.max(1, timeout.toSeconds())));
        return run(limited, tenant, work);
    }

    /**
     * REPEATABLE READ: 스냅샷은 첫 문장에서 정해진다. 다른 트랜잭션의 커밋과 부딪힌 실패는 롤백된 뒤 {@link ConcurrentWriteConflict}로 옮긴다 —
     * 유일성 위반(23505, 감사 append가 스냅샷의 머리로 다음 seq를 쓰다 이미 커밋된 행과 겹친다, 스프링 {@link DuplicateKeyException})과
     * 직렬화·잠금 실패(40001 등, {@link ConcurrencyFailureException}). JDBC 예외는 저장소 계층이 스프링 예외로 번역한 것을 본다(이 클래스는
     * {@code java.sql}을 쓰지 않는다 — ArchitectureRulesTest). 다른 실패는 그대로 던진다.
     */
    @Override
    public <T> T inTenantRepeatableRead(TenantId tenant, Supplier<T> work) {
        TransactionTemplate repeatable = new TransactionTemplate(transactionManager);
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            return run(repeatable, tenant, work);
        } catch (DuplicateKeyException e) {
            throw new ConcurrentWriteConflict("23505", e);
        } catch (ConcurrencyFailureException e) {
            throw new ConcurrentWriteConflict("40001", e);
        }
    }

    @Override
    public <T> T inNewTenantTransaction(TenantId tenant, Supplier<T> work) {
        TransactionTemplate fresh = new TransactionTemplate(transactionManager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return run(fresh, tenant, work);
    }

    private static <T> T run(TransactionTemplate t, TenantId tenant, Supplier<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        TenantContext.runWith(tenant, () -> result.set(t.execute(status -> work.get())));
        return result.get();
    }
}
