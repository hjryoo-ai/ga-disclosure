package com.ga.disclosure.workflow;

import java.util.Objects;

/**
 * 유스케이스의 행위자: {@code audit_log.actor_subject}·{@code actor_role}. 인가는 Phase 6이므로 지금은 호출자(운영자 CLI 등)가
 * 넘긴 값을 그대로 기록한다.
 */
public record Actor(String subject, String role) {

    public Actor {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(role, "role");
        if (subject.isBlank() || role.isBlank()) {
            throw new IllegalArgumentException("actor subject and role are required");
        }
    }
}
