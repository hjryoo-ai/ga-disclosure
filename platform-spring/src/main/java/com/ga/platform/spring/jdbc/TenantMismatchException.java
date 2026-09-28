package com.ga.platform.spring.jdbc;

import com.ga.platform.core.tenant.TenantId;

/** 트랜잭션을 연 테넌트와 현재 {@code TenantContext}의 테넌트가 다르다(트랜잭션 안에서 재바인딩). */
public final class TenantMismatchException extends IllegalStateException {

    public TenantMismatchException(TenantId transactionTenant, TenantId contextTenant) {
        super("transaction is bound to tenant " + transactionTenant + " but TenantContext is " + contextTenant);
    }
}
