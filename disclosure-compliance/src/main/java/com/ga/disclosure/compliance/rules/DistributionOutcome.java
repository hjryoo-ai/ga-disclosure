package com.ga.disclosure.compliance.rules;

import com.ga.platform.core.tenant.TenantId;

import java.util.Objects;

/** 테넌트 1곳에 대한 번들 배포 결과. */
public record DistributionOutcome(TenantId tenant, String bundleId, Result result, String message) {

    /** INSERTED: 복제본 삽입, NOOP: 같은 번들이 이미 있음(감사 행만), REJECTED: 규칙 위반으로 롤백. */
    public enum Result {
        INSERTED,
        NOOP,
        REJECTED
    }

    public DistributionOutcome {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(bundleId, "bundleId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(message, "message");
    }
}
