package com.ga.disclosure.workflow.sign;

import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.NotAnEntry;
import com.ga.platform.core.tenant.TenantId;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 공개 서명 경로의 테넌트 분당 한도(6A 계획 §5.3·§5.4, 룰 {@code publicSign.tenantRatePerMinute}): 알려진 테넌트만, 그 테넌트를 바인딩해 오늘(KST) 유효 룰에서
 * 읽는다. 응답 패딩 하한은 룰이 아니라 배포 설정이다(승인 Q6). 호출자는 공개 경로의 게이트 하나다.
 */
public final class PublicSignLimits {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RuleResolver rules;
    private final WorkflowTransactions transactions;
    private final Clock clock;

    public PublicSignLimits(RuleResolver rules, WorkflowTransactions transactions, Clock clock) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @NotAnEntry("public sign gate plumbing: reads the known tenant's rate limit before any token is checked; reads rule data only")
    public int perMinute(TenantId tenant) {
        return transactions.inTenant(tenant, () -> rules.resolve(tenant, LocalDate.ofInstant(clock.instant(), SEOUL)).publicSignTenantRatePerMinute());
    }
}
