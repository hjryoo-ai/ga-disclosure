package com.ga.disclosure.infra.tx;

import com.ga.disclosure.compliance.rules.TenantTransactions;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
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

    private static <T> T run(TransactionTemplate t, TenantId tenant, Supplier<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        TenantContext.runWith(tenant, () -> result.set(t.execute(status -> work.get())));
        return result.get();
    }
}
