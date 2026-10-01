package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.validation.ValidationResult;

import java.util.List;
import java.util.Optional;

/**
 * 봉인 판정(3A W5, 3B 봉인 조건 ④·⑤): SEAL 단계 결과 중 봉인을 막는 것 — 오버라이드 불가 실패 전부, 그리고 그 규칙·대상 해시에 맞고
 * <b>확인서의 현재 고정 룰 버전 2종과 같은</b> 관리자 승인({@link Review})이 없는 오버라이드 가능 실패(3A 수용심사 §3-8). 재기준 뒤 옛 승인은
 * 남아 있어도 여기서 인정되지 않는다. 중간 단계(COMPARE·GRADE·REASON)는 오버라이드 가능 실패로 막지 않는다(3A 계획 Q2).
 */
public final class SealGate {

    private SealGate() {
    }

    public static List<ValidationResult> unapproved(List<ValidationResult> sealResults, List<Review> reviews, RuleVersionId pinnedRule,
                                                    Optional<RuleVersionId> pinnedTenantRule) {
        return sealResults.stream()
                .filter(r -> !r.passed())
                .filter(r -> !r.overridable() || reviews.stream()
                        .noneMatch(v -> v.covers(r.ruleId(), r.subjectHash().orElseThrow(), pinnedRule, pinnedTenantRule)))
                .toList();
    }

    /** 실패마다 그것을 덮는 승인(봉인 성공 시 플래그 해소자 = 승인자, 3A 수용심사 §3-7). 덮는 승인이 없으면 빈 값. */
    public static Optional<Review> approvalFor(ValidationResult failure, List<Review> reviews, RuleVersionId pinnedRule,
                                               Optional<RuleVersionId> pinnedTenantRule) {
        if (failure.passed() || !failure.overridable()) {
            return Optional.empty();
        }
        return reviews.stream()
                .filter(v -> v.covers(failure.ruleId(), failure.subjectHash().orElseThrow(), pinnedRule, pinnedTenantRule))
                .reduce((first, later) -> later);     // 같은 대상의 승인이 여럿이면 가장 나중 것(저장소는 승인 시각 순으로 준다)
    }
}
