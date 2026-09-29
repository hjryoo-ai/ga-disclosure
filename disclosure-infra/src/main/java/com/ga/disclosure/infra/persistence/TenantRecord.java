package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.enums.GateMode;
import com.ga.disclosure.domain.enums.IssuerMode;
import com.ga.platform.core.tenant.TenantId;

import java.util.Objects;

/**
 * {@code tenant} 행.
 *
 * @param paramsJson 사규 파라미터 JSON 원문({@code gateRequiresManager} 등). 해석은 Phase 1 이후.
 */
public record TenantRecord(
        TenantId tenantId,
        String name,
        String engineBaseUrl,
        String status,
        boolean largeGa,
        IssuerMode issuerMode,
        GateMode gateMode,
        String paramsJson) {

    public TenantRecord {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(engineBaseUrl, "engineBaseUrl");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(issuerMode, "issuerMode");
        Objects.requireNonNull(gateMode, "gateMode");
        Objects.requireNonNull(paramsJson, "paramsJson");
    }
}
