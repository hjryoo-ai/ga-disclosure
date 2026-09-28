package com.ga.platform.spring.jdbc;

/** {@link TenantSessionBinder}가 연 트랜잭션 밖에서 DB 접근을 시도했다. */
public final class OutsideTenantTransactionException extends IllegalStateException {

    public OutsideTenantTransactionException(String detail) {
        super("DB access outside a tenant-bound transaction is rejected: " + detail);
    }
}
