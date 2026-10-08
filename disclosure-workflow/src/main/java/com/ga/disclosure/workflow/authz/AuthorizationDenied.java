package com.ga.disclosure.workflow.authz;

import java.util.Objects;

/**
 * 인가 거부(존재 누설 없는 404로 수렴 — 5 수용심사 결정 1). 사유는 감사에만 남는다. 메시지에 대상 ID·주체를 넣지 않는다.
 */
public final class AuthorizationDenied extends RuntimeException {

    /** 거부 사유(감사 {@code AUTHZ_DENIED.detail.reason}). */
    public enum Reason {
        /** {@code identity_link} 행이 없다. */
        NO_LINK,
        /** 이 채널로 이 행위를 할 역할이 없다(사람 역할의 {@code /internal}, 서비스 주체의 {@code /api} 포함). */
        CHANNEL,
        /** 역할은 있으나 이 행위를 허가하는 역할이 아니다. */
        ROLE,
        /** 대상이 범위(자기 확인서·조직 접두) 밖이다. */
        SCOPE,
        /** 대상이 없다(다른 테넌트 포함). */
        NOT_FOUND
    }

    private final Action action;
    private final Reason reason;

    public AuthorizationDenied(Action action, Reason reason) {
        super("not authorized");
        this.action = Objects.requireNonNull(action, "action");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Action action() {
        return action;
    }

    public Reason reason() {
        return reason;
    }
}
