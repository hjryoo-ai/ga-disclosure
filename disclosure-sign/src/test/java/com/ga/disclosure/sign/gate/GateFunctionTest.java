package com.ga.disclosure.sign.gate;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.SignerRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.ga.disclosure.domain.enums.SignerRole.AGENT;
import static com.ga.disclosure.domain.enums.SignerRole.CUSTOMER;
import static com.ga.disclosure.domain.enums.SignerRole.MANAGER;
import static org.assertj.core.api.Assertions.assertThat;

/** G13: 게이트 산식 — {@code gateRequiresManager} 양쪽 × 상태 10 × 서명한 역할 부분집합(signerSet 2종) 전수. */
class GateFunctionTest {

    private static final List<SignerRole> THREE = List.of(CUSTOMER, AGENT, MANAGER);
    private static final List<SignerRole> TWO = List.of(CUSTOMER, AGENT);

    static Stream<Arguments> everyCombination() {
        List<Arguments> out = new ArrayList<>();
        for (boolean requiresManager : new boolean[]{true, false}) {
            for (DisclosureStatus status : DisclosureStatus.values()) {
                for (List<SignerRole> set : List.of(THREE, TWO)) {
                    for (int mask = 0; mask < (1 << set.size()); mask++) {
                        Set<SignerRole> signed = EnumSet.noneOf(SignerRole.class);
                        for (int i = 0; i < set.size(); i++) {
                            if ((mask & (1 << i)) != 0) {
                                signed.add(set.get(i));
                            }
                        }
                        out.add(Arguments.of(requiresManager, status, set, signed));
                    }
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "requiresManager={0} {1} set={2} signed={3}")
    @MethodSource("everyCombination")
    void gateFollowsStatusAndSignatures(boolean requiresManager, DisclosureStatus status, List<SignerRole> set, Set<SignerRole> signed) {
        GateView v = GateFunction.evaluate(status, set, signed, requiresManager);
        switch (status) {
            case COMPLETED -> {
                assertThat(v.status()).isEqualTo(GateStatus.COMPLETED);
                assertThat(v.pendingRoles()).isEmpty();
                assertThat(v.gateSatisfied()).isTrue();
            }
            case SEALED, PARTIALLY_SIGNED -> {
                assertThat(v.status()).isEqualTo(GateStatus.PENDING);
                assertThat(v.pendingRoles()).isEqualTo(set.stream().filter(r -> !signed.contains(r)).toList());
                boolean nonManagersDone = set.stream().filter(r -> r != MANAGER).allMatch(signed::contains);
                assertThat(v.gateSatisfied()).isEqualTo(!requiresManager && nonManagersDone);
            }
            default -> {
                assertThat(v.status()).isEqualTo(GateStatus.NONE);
                assertThat(v.pendingRoles()).isEmpty();
                assertThat(v.gateSatisfied()).isFalse();
            }
        }
    }

    @Test
    void namedCases() {
        // 기본(관리자 필요): 고객·설계사 서명 뒤에도 관리자 확인 전에는 불충족
        assertThat(GateFunction.evaluate(DisclosureStatus.PARTIALLY_SIGNED, THREE, Set.of(CUSTOMER, AGENT), true))
                .isEqualTo(new GateView(GateStatus.PENDING, List.of(MANAGER), false));
        // 사규가 끈 테넌트: 고객·설계사 서명부터 청약 진행 허용(관리자 확인은 여전히 대기)
        assertThat(GateFunction.evaluate(DisclosureStatus.PARTIALLY_SIGNED, THREE, Set.of(CUSTOMER, AGENT), false))
                .isEqualTo(new GateView(GateStatus.PENDING, List.of(MANAGER), true));
        // OFF 테넌트(signerSet 2자): 둘 다 서명했으면 대기 없음
        assertThat(GateFunction.evaluate(DisclosureStatus.PARTIALLY_SIGNED, TWO, Set.of(CUSTOMER, AGENT), true))
                .isEqualTo(new GateView(GateStatus.PENDING, List.of(), false));
        assertThat(GateFunction.evaluate(DisclosureStatus.SEALED, THREE, Set.of(), false).pendingRoles()).containsExactly(CUSTOMER, AGENT, MANAGER);
    }
}
