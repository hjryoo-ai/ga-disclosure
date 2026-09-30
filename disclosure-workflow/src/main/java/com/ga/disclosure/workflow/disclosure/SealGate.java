package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.rules.validation.ValidationResult;

import java.util.List;

/**
 * 봉인 판정(3B SEAL 조건이 쓴다, 3A W5): SEAL 단계 결과 중 봉인을 막는 것 — 오버라이드 불가 실패 전부, 그리고 그 규칙·대상 해시에 맞는
 * 관리자 승인({@link Review})이 <b>없는</b> 오버라이드 가능 실패. 중간 단계(COMPARE·GRADE·REASON)는 오버라이드 가능 실패로 막지 않는다
 * (3A 계획 Q2) — 승인 확인은 여기서 한다.
 */
public final class SealGate {

    private SealGate() {
    }

    public static List<ValidationResult> unapproved(List<ValidationResult> sealResults, List<Review> reviews) {
        return sealResults.stream()
                .filter(r -> !r.passed())
                .filter(r -> !r.overridable() || reviews.stream().noneMatch(v -> v.covers(r.ruleId(), r.subjectHash().orElseThrow())))
                .toList();
    }
}
