package com.ga.disclosure.infra;

import com.ga.disclosure.infra.crypto.LocalFileKeyProvider;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.platform.core.tenant.TenantId;

import java.util.Objects;

/**
 * 시험 조립의 KEK 어댑터를 실행 중에 바꾼다(Phase 8 이행 시험): 전역 시절 KEK로 감싼 데이터를 만든 뒤 테넌트 KEK 어댑터(이중 읽기)로 바꾼다.
 */
final class SwitchableKeys implements KeyProviderPort {

    private volatile KeyProviderPort delegate;

    SwitchableKeys(KeyProviderPort initial) {
        this.delegate = Objects.requireNonNull(initial, "initial");
    }

    void use(KeyProviderPort next) {
        this.delegate = Objects.requireNonNull(next, "next");
    }

    /** 전역 시절 동작(Phase 2~7): 테넌트와 무관한 KEK 하나로 감싼다. 그 전에 테넌트 KEK로 감싼 키(시험 조립이 먼저 만든 고객)는 {@code earlier}로 푼다. */
    static KeyProviderPort globalEra(LocalFileKeyProvider legacy, KeyProviderPort earlier) {
        return new KeyProviderPort() {
            @Override
            public String currentKekId(TenantId tenant) {
                return legacy.currentKekId();
            }

            @Override
            public byte[] wrap(TenantId tenant, String keyId, String kekId, byte[] dataKey) {
                return legacy.wrap(tenant, keyId, kekId, dataKey);
            }

            @Override
            public byte[] unwrap(TenantId tenant, String keyId, String kekId, byte[] wrapped) {
                return kekId.equals(legacy.currentKekId()) ? legacy.unwrap(tenant, keyId, kekId, wrapped) : earlier.unwrap(tenant, keyId, kekId, wrapped);
            }

            @Override
            public byte[] rewrap(TenantId tenant, String keyId, String fromKekId, String toKekId, byte[] wrapped) {
                throw new UnsupportedOperationException("the global era had no rewrap");
            }
        };
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
}
