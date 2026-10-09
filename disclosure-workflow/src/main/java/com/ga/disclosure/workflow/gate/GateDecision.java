package com.ga.disclosure.workflow.gate;

import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.vo.RuleVersionId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 게이트 판정(개인정보 없음 — 번호·역할·룰 버전만). 확인서 번호와 룰 버전은 근거가 되는 확인서가 있을 때(충족·대기)만 싣는다 — 고객 불일치·모호·근거 없음에는
 * 다른 고객의 확인서를 가리키지 않는다.
 */
public record GateDecision(Verdict decision, Reason reason, Optional<String> disclosureNo, List<SignerRole> pendingRoles,
                           Optional<RuleVersionId> ruleVersionId) {

    public enum Verdict { ALLOWED, BLOCKED }

    public enum Reason { SATISFIED, NO_DISCLOSURE, CUSTOMER_MISMATCH, AMBIGUOUS, PENDING }

    public GateDecision {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(disclosureNo, "disclosureNo");
        pendingRoles = List.copyOf(pendingRoles);
        Objects.requireNonNull(ruleVersionId, "ruleVersionId");
        if ((decision == Verdict.ALLOWED) != (reason == Reason.SATISFIED)) {
            throw new IllegalArgumentException("ALLOWED is exactly SATISFIED");
        }
    }

    static GateDecision blocked(Reason reason) {
        return new GateDecision(Verdict.BLOCKED, reason, Optional.empty(), List.of(), Optional.empty());
    }
}
