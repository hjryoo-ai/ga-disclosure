package com.ga.disclosure.compliance.rules;

import com.ga.platform.core.tenant.TenantId;

import java.util.List;
import java.util.UUID;

/** 번들 대사 결과(테넌트 1곳): 검사한 복제본 수와 드리프트(플래그 ID·대상·사유). */
public record ReconcileReport(TenantId tenant, int checked, List<Drift> drifts) {

    public record Drift(String targetKind, String targetId, UUID flagId, List<String> problems) {
        public Drift {
            problems = List.copyOf(problems);
        }
    }

    public ReconcileReport {
        drifts = List.copyOf(drifts);
    }
}
