package com.ga.disclosure.compliance.rules;

import com.ga.platform.core.tenant.TenantId;

import java.util.function.Supplier;

/**
 * 테넌트 바인딩 트랜잭션 경계(infra가 구현: TenantContext 바인딩 + TenantSessionBinder 트랜잭션). 룰 거버넌스는 테넌트마다
 * 한 트랜잭션으로 실행된다 — 한 테넌트의 실패가 다른 테넌트의 결과를 되돌리지 않고, 한 테넌트 안에서는 전부 아니면 전무다.
 */
public interface TenantTransactions {

    <T> T inTenant(TenantId tenant, Supplier<T> work);
}
