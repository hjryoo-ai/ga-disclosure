package com.ga.disclosure.sign.gate;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.SignerRole;

import java.util.Collection;
import java.util.List;

/**
 * 청약 게이트 산식(설계서 §4.4, 4 계획 §7.5, 순수). COMPLETED면 충족·대기 없음. 봉인·일부 서명이면 대기 = signerSet − 서명한 역할(순서 유지),
 * 충족은 {@code gateRequiresManager = true}이면 없음(완료만), {@code false}이면 MANAGER 외 signerSet 전원이 서명한 때부터다(관리자 확인은
 * 이후에도 반드시 완료돼야 한다). 그 밖의 상태(봉인 전·무효·정정·만료)는 해당 없음·불충족. 어느 확인서를 고를지는 Phase 6 API.
 */
public final class GateFunction {

    private GateFunction() {
    }

    public static GateView evaluate(DisclosureStatus status, List<SignerRole> signerSet, Collection<SignerRole> signed,
                                    boolean gateRequiresManager) {
        return switch (status) {
            case COMPLETED -> new GateView(GateStatus.COMPLETED, List.of(), true);
            case SEALED, PARTIALLY_SIGNED -> {
                List<SignerRole> pending = signerSet.stream().filter(r -> !signed.contains(r)).toList();
                boolean satisfied = !gateRequiresManager && pending.stream().allMatch(r -> r == SignerRole.MANAGER);
                yield new GateView(GateStatus.PENDING, pending, satisfied);
            }
            case DRAFT, COMPARED, GRADED, REASONED, VOID, SUPERSEDED, EXPIRED -> new GateView(GateStatus.NONE, List.of(), false);
        };
    }
}
