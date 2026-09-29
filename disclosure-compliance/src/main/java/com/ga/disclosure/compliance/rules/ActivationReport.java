package com.ga.disclosure.compliance.rules;

import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.platform.core.tenant.TenantId;

import java.time.LocalDate;
import java.util.List;

/**
 * 활성화 배치 결과(테넌트 1곳).
 *
 * @param expiredUnactivated APPROVED인 채 적용 구간이 이미 끝나 활성화하지 않은 룰(운영 경보 대상)
 */
public record ActivationReport(TenantId tenant, LocalDate asOf, List<RuleVersionId> retired, List<RuleVersionId> activated,
                               List<RuleVersionId> expiredUnactivated) {

    public ActivationReport {
        retired = List.copyOf(retired);
        activated = List.copyOf(activated);
        expiredUnactivated = List.copyOf(expiredUnactivated);
    }
}
