package com.ga.disclosure.rules.testing;

import com.ga.disclosure.domain.vo.RuleVersionId;
import java.util.Optional;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.disclosure.rules.version.RuleVersionPort;
import com.ga.platform.core.tenant.TenantId;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 테스트 픽스처: DB 배타 제약이 없는 인메모리 포트. 겹치는 룰 2건을 그대로 돌려줄 수 있어 해석기의 Ambiguous fail-fast를
 * DB와 독립적으로 검증한다(Phase 1 C2). 조회 의미는 infra 어댑터와 같다(ACTIVE·RETIRED이고 구간이 기준일 포함).
 */
public final class InMemoryRuleVersionPort implements RuleVersionPort {

    private final List<RuleVersion> rules = new ArrayList<>();

    public InMemoryRuleVersionPort add(RuleVersion rule) {
        rules.add(rule);
        return this;
    }

    @Override
    public List<RuleVersion> findActive(TenantId tenant, RuleScope scope, LocalDate asOf) {
        return rules.stream()
                .filter(r -> r.scope() == scope)
                .filter(r -> r.status() == RuleStatus.ACTIVE || r.status() == RuleStatus.RETIRED)
                .filter(r -> r.covers(asOf))
                .toList();
    }

    @Override
    public Optional<RuleVersion> findById(TenantId tenant, RuleVersionId id) {
        return rules.stream().filter(r -> r.id().equals(id)).findFirst();
    }
}
