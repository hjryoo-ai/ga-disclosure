package com.ga.disclosure.compliance.rules;

import java.util.Objects;

/**
 * 운영자 CLI의 행위자. 룰 거버넌스(배포·승인·활성화·대사)는 운영자 CLI 전용이다(6A에 HTTP 노출 없음) — CLI 인자로 받은 식별자를
 * {@code audit_log.actor_subject}에, 역할 {@value #ROLE}을 {@code actor_role}에 남긴다(Phase 1 지시문 §3, 6A 승인 Q9와 같은 감사 역할).
 */
public record Operator(String subject) {

    public static final String ROLE = "OPERATOR";

    public Operator {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("operator subject is required");
        }
    }
}
