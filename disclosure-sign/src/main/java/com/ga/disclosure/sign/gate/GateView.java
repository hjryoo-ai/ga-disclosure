package com.ga.disclosure.sign.gate;

import com.ga.disclosure.domain.enums.SignerRole;

import java.util.List;
import java.util.Objects;

/** 게이트 판정 결과(설계서 §4.4). {@code pendingRoles}는 signerSet 순서. */
public record GateView(GateStatus status, List<SignerRole> pendingRoles, boolean gateSatisfied) {

    public GateView {
        Objects.requireNonNull(status, "status");
        pendingRoles = List.copyOf(pendingRoles);
    }
}
