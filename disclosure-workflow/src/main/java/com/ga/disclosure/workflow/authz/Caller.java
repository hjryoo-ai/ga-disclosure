package com.ga.disclosure.workflow.authz;

import com.ga.platform.core.tenant.TenantId;

import java.util.Objects;

/**
 * 호출자 — 토큰(또는 CLI·서명 토큰)에서 오는 <b>전부</b>다: 테넌트·주체·채널. 역할이 없다 — 역할은 {@link AuthorizationPort}가
 * {@code identity_link}에서 정하고, 허가를 준 역할이 감사 행위자가 된다(6A 계획 §3.1).
 */
public record Caller(TenantId tenant, String subject, Channel channel) {

    public Caller {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(channel, "channel");
        if (subject.isBlank() || subject.length() > 200) {
            throw new IllegalArgumentException("caller subject must be 1..200 characters");
        }
    }

    /** 운영자 CLI: 역할은 OPERATOR(승인 Q9 — {@code --role}은 폐기). */
    public static Caller cli(TenantId tenant, String operator) {
        return new Caller(tenant, operator, Channel.CLI);
    }

    /** 사람 역할의 HTTP 호출({@code /api/v1}). */
    public static Caller api(TenantId tenant, String subject) {
        return new Caller(tenant, subject, Channel.API);
    }

    /** 서비스 주체의 HTTP 호출({@code /internal/v1}). */
    public static Caller internal(TenantId tenant, String subject) {
        return new Caller(tenant, subject, Channel.INTERNAL);
    }

    @Override
    public String toString() {
        return "Caller[" + tenant + ", " + channel + "]";       // 주체는 감사에만 남긴다
    }
}
