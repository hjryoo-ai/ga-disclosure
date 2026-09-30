package com.ga.disclosure.workflow;

import com.ga.platform.core.tenant.TenantId;

import java.util.function.Supplier;

/**
 * 테넌트에 바인딩된 트랜잭션 하나(포트, infra가 구현). 안에서 호출하는 저장소·감사 포트는 전부 같은 트랜잭션이다(설계서 §6.7).
 */
public interface WorkflowTransactions {

    <T> T inTenant(TenantId tenant, Supplier<T> work);
}
