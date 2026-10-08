package com.ga.disclosure.workflow.flag;

import com.ga.disclosure.rules.resolve.FlagAssignee;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 플래그가 열릴 때 룰에서 복사해 고정하는 값(6B 계획 §7, V14 GD134): 담당 역할·설계사 가시성·기한. 룰을 해석할 수 없으면 닫힌 쪽 기본값
 * {@link #failClosed()}(준법·보이지 않음·기한 없음).
 *
 * @param fromRule 룰에서 왔는가(false = 기본값)
 */
public record FlagPolicy(FlagAssignee assignedRole, boolean visibleToAgent, Optional<Instant> dueAt, boolean fromRule) {

    public FlagPolicy {
        Objects.requireNonNull(assignedRole, "assignedRole");
        Objects.requireNonNull(dueAt, "dueAt");
    }

    public static FlagPolicy failClosed() {
        return new FlagPolicy(FlagAssignee.COMPLIANCE, false, Optional.empty(), false);
    }
}
