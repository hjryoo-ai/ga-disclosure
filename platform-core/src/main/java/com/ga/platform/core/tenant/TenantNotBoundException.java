package com.ga.platform.core.tenant;

/** {@link TenantContext}에 테넌트가 바인딩되지 않은 상태에서 테넌트가 필요한 작업을 시도했다. */
public final class TenantNotBoundException extends IllegalStateException {

    public TenantNotBoundException() {
        super("TenantContext is not bound: wrap the call in TenantContext.runWith(tenantId, ...)");
    }
}
