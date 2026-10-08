package com.ga.disclosure.workflow.authz;

/**
 * 호출이 들어온 길(6A 승인 Q15). HTTP 접두가 채널을 정한다 — {@code /api/v1} = {@link #API}(사람 역할), {@code /internal/v1} = {@link #INTERNAL}
 * (서비스 주체), 공개 서명 = {@link #SIGN_TOKEN}. 운영자 CLI는 {@link #CLI}이고 역할은 언제나 {@code OPERATOR}다(승인 Q9).
 */
public enum Channel {
    API,
    INTERNAL,
    CLI,
    SIGN_TOKEN
}
