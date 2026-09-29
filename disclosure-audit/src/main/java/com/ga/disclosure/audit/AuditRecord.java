package com.ga.disclosure.audit;

import com.ga.platform.core.tenant.TenantId;

import java.util.Objects;

/** 저장된 감사 행: 테넌트별 단조 {@code seq}와 해시 연쇄({@code entryHash = H(prevHash ‖ JCS(entry))}). */
public record AuditRecord(TenantId tenantId, long seq, AuditEntry entry, String prevHash, String entryHash) {

    public AuditRecord {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(prevHash, "prevHash");
        Objects.requireNonNull(entryHash, "entryHash");
        if (seq < 1) {
            throw new IllegalArgumentException("seq starts at 1");
        }
    }
}
