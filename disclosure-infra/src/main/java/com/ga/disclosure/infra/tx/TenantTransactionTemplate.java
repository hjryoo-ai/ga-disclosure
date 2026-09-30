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

    private final TransactionTemplate template;

    public TenantTransactionTemplate(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T inTenant(TenantId tenant, Supplier<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        TenantContext.runWith(tenant, () -> result.set(template.execute(status -> work.get())));
        return result.get();
    }
}
