package com.ga.disclosure.workflow.authz;

/**
 * 역할(V12 {@code identity_link.roles}의 닫힌 집합 + 채널에서만 생기는 둘). 역할마다 닿을 수 있는 채널은 하나다 — 사람 역할은 {@code /api},
 * 서비스 주체는 {@code /internal}(6A 승인 Q15), {@link #OPERATOR}는 CLI, {@link #CUSTOMER}는 서명 토큰.
 */
public enum Role {
    COMPLIANCE(Channel.API),
    MANAGER(Channel.API),
    AGENT(Channel.API),
    SCHEDULER(Channel.INTERNAL),
    FEED_CONSUMER(Channel.INTERNAL),
    OPERATOR(Channel.CLI),
    CUSTOMER(Channel.SIGN_TOKEN);

    private final Channel channel;

    Role(Channel channel) {
        this.channel = channel;
    }

    public Channel channel() {
        return channel;
    }

    /** {@code identity_link}에 둘 수 있는 역할인가(OPERATOR·CUSTOMER는 채널에서만 생긴다). */
    public boolean linkable() {
        return channel == Channel.API || channel == Channel.INTERNAL;
    }
}
