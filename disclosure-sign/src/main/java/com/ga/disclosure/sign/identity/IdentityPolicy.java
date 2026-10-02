package com.ga.disclosure.sign.identity;

import com.ga.disclosure.domain.enums.IdentityMethod;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 본인확인 정책(설계서 §6.5, 4 계획 §2.4): 룰 {@code identityCheck[channel]}의 수단을 <b>전부</b> 통과해야 서명할 수 있다. 결과만 다룬다 —
 * 입력값(생년월일 등)은 이 모듈에 들어오지 않는다. PROVIDER는 v2(인정 전자서명 사업자) 예약이라 v1 룰이 요구하면 설정 오류다.
 */
public final class IdentityPolicy {

    private IdentityPolicy() {
    }

    /** 요구 수단 중 아직 통과하지 않은 것(룰 순서). */
    public static List<IdentityMethod> missing(List<IdentityMethod> required, Collection<IdentityMethod> passed) {
        requireSupported(required);
        Set<IdentityMethod> done = passed.isEmpty() ? EnumSet.noneOf(IdentityMethod.class) : EnumSet.copyOf(passed);
        return required.stream().filter(m -> !done.contains(m)).toList();
    }

    public static boolean complete(List<IdentityMethod> required, Collection<IdentityMethod> passed) {
        return missing(required, passed).isEmpty();
    }

    /** 실패 1회 뒤의 실패 횟수가 상한에 닿았는가(세션 취소 + 플래그 {@code IDENTITY_FAILED}). */
    public static boolean exhausted(int failuresAfter, int maxFailures) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("identityCheck.maxFailures must be at least 1: " + maxFailures);
        }
        return failuresAfter >= maxFailures;
    }

    private static void requireSupported(List<IdentityMethod> required) {
        if (required.contains(IdentityMethod.PROVIDER)) {
            throw new IllegalArgumentException("identity method PROVIDER is reserved for v2 (certified e-signature providers)");
        }
        Set<IdentityMethod> seen = EnumSet.noneOf(IdentityMethod.class);
        for (IdentityMethod m : required) {
            if (!seen.add(m)) {
                throw new IllegalArgumentException("identity method repeats: " + m);
            }
        }
    }
}
