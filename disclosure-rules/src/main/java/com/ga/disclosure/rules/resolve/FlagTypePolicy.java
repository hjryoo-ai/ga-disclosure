package com.ga.disclosure.rules.resolve;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 플래그 유형 하나의 준법 큐 정책(룰 {@code complianceQueue.types[type]}, 6B 계획 §7). 담당·설계사 가시성·기한은 플래그가 생길 때 복사해
 * 고정한다(V14). {@code resolutionCodes}가 비면 수동 해소가 없다 — 문서 상태·전용 유스케이스로만 닫힌다.
 *
 * @param slaHours 열린 시각부터 기한까지 시간, 없으면 기한 없음(실값 미정 — TODO(confirm#18))
 */
public record FlagTypePolicy(FlagAssignee assignedRole, OptionalInt slaHours, boolean visibleToAgent,
                             List<LifecycleReasonRule> resolutionCodes, boolean requiresEvidence) {

    public FlagTypePolicy {
        Objects.requireNonNull(assignedRole, "assignedRole");
        Objects.requireNonNull(slaHours, "slaHours");
        resolutionCodes = List.copyOf(resolutionCodes);
        if (slaHours.isPresent() && slaHours.getAsInt() < 1) {
            throw new IllegalArgumentException("slaHours ≥ 1");
        }
        if (requiresEvidence && resolutionCodes.isEmpty()) {
            throw new IllegalArgumentException("requiresEvidence needs a manual resolution code");
        }
    }

    public boolean manuallyResolvable() {
        return !resolutionCodes.isEmpty();
    }

    public boolean allowsResolution(String code) {
        return resolutionCodes.stream().anyMatch(r -> r.code().equals(code));
    }
}
