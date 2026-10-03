package com.ga.disclosure.workflow;

import java.util.Objects;

/**
 * 유스케이스의 행위자: {@code audit_log.actor_subject}·{@code actor_role}. 6A부터 진입점은 이것을 인자로 받지 않는다 —
 * {@link com.ga.disclosure.workflow.authz.AuthorizationPort}가 호출자({@code Caller})를 인가하고 허가를 준 역할로 만들어 돌려준다.
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
