package com.ga.disclosure.workflow.onboarding;

import com.ga.disclosure.rules.resolve.ResolutionFailure;
import com.ga.disclosure.rules.resolve.RuleResolutionException;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.NotAnEntry;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 룰 없는 테넌트의 쓰기(6B 이월 ①, 8 계획 승인): 오늘(KST) 시행 중인 GLOBAL 룰이 없으면 쓰기 요청은 유스케이스·멱등 청구 전에 503
 * {@code TENANT_RULES_NOT_ACTIVE}다(감사 없음, 멱등 키를 묶지 않음). 6A부터 그런 요청은 멱등 청구가 룰을 읽다 500이었다. 다른 해석 실패(둘 이상 매칭 등)는
 * 데이터 이상이라 그대로 실패한다. 온보딩 순서(룰 배포·승인·활성화 → IdP 주체 등록)는 런북.
 */
public final class TenantRulesStatus {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RuleResolver rules;
    private final WorkflowTransactions transactions;
    private final Clock clock;

    public TenantRulesStatus(RuleResolver rules, WorkflowTransactions transactions, Clock clock) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @NotAnEntry("write-path plumbing (Phase 8, 6B carry-over 1): whether a GLOBAL rule is in force for the bound caller's own tenant, before the "
            + "idempotency claim; reads rule data only")
    public boolean inForce(Caller caller) {
        Objects.requireNonNull(caller, "caller");
        LocalDate today = clock.instant().atZone(SEOUL).toLocalDate();
        try {
            transactions.inTenant(caller.tenant(), () -> rules.resolve(caller.tenant(), today));
            return true;
        } catch (RuleResolutionException e) {
            if (e.failure() == ResolutionFailure.NO_GLOBAL_RULE) {
                return false;
            }
            throw e;
        }
    }
}
