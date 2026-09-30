package com.ga.disclosure.compliance.rules;

import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.platform.core.tenant.TenantId;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 활성화 배치 결과(테넌트 1곳).
 *
 * @param missed APPROVED인 채 적용 구간이 이미 끝나 활성화하지 않은 룰과 그 {@code RULE_ACTIVATION_MISSED} 플래그
 */
public record ActivationReport(TenantId tenant, LocalDate asOf, List<RuleVersionId> retired, List<RuleVersionId> activated,
                               List<Missed> missed) {

    /** @param flagCreated 이번 실행에서 새로 올렸으면 true, 이미 열린 플래그가 있었으면 false */
    public record Missed(RuleVersionId rule, UUID flagId, boolean flagCreated) {
    }

    public ActivationReport {
        retired = List.copyOf(retired);
        activated = List.copyOf(activated);
        missed = List.copyOf(missed);
    }
}
