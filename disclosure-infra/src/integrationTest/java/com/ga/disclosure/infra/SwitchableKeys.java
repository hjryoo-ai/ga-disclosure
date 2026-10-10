package com.ga.disclosure.infra;

import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.platform.core.tenant.TenantId;

import java.util.Objects;

/** 시험 조립의 KEK 어댑터를 실행 중에 바꾼다(Phase 8 회전 시험 — 옛 KEK 바이트가 없는 출처로 바꿔 열어 본다). */
final class SwitchableKeys implements KeyProviderPort {

    private volatile KeyProviderPort delegate;

    SwitchableKeys(KeyProviderPort initial) {
        this.delegate = Objects.requireNonNull(initial, "initial");
    }

    void use(KeyProviderPort next) {
        this.delegate = Objects.requireNonNull(next, "next");
    }

    @Override
    public String currentKekId(TenantId tenant) {
        return delegate.currentKekId(tenant);
    }

    @Override
    public byte[] wrap(TenantId tenant, String keyId, String kekId, byte[] dataKey) {
        return delegate.wrap(tenant, keyId, kekId, dataKey);
    }

    @Override
    public byte[] unwrap(TenantId tenant, String keyId, String kekId, byte[] wrapped) {
        return delegate.unwrap(tenant, keyId, kekId, wrapped);
    }

    @Override
    public byte[] rewrap(TenantId tenant, String keyId, String fromKekId, String toKekId, byte[] wrapped) {
        return delegate.rewrap(tenant, keyId, fromKekId, toKekId, wrapped);
    }

    @Override
    public boolean unwraps(TenantId tenant, String keyId, String kekId, byte[] wrapped) {
        return delegate.unwraps(tenant, keyId, kekId, wrapped);
    }
}
