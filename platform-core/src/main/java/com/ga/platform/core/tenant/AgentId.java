package com.ga.platform.core.tenant;

import java.util.regex.Pattern;

/**
 * 설계사(또는 관리자) 식별자. 테넌트 안에서 유일하다.
 *
 * <p>토큰 클레임에서 만들지 않는다 — OIDC subject → {@code identity_link} 조회 결과로만 만든다(CLAUDE.md 절대 규칙 5).
 */
public record AgentId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public AgentId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid agent id: " + value);
        }
    }

    public static AgentId of(String value) {
        return new AgentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
