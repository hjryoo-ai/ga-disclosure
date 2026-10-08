package com.ga.disclosure.infra;

import com.ga.disclosure.infra.authz.IdentityLinkAuthorization;
import com.ga.disclosure.infra.persistence.AuditLogRepository;
import com.ga.disclosure.infra.persistence.AuthzFactsRepository;
import com.ga.disclosure.infra.persistence.IdentityLinkRepository;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.tx.TenantTransactionTemplate;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantSessionBinder;

import java.time.Clock;

/**
 * 통합 테스트의 행위자 상수 → 호출자(6A 3단계). 사람 역할(설계사·관리자·준법)은 {@code /api} 채널 — 역할은 {@code identity_link}가 정하므로 조립이
 * 그 주체의 연결을 심어 둔다. 나머지(운영자·배치)는 운영자 CLI 채널(역할 OPERATOR, 승인 Q9).
 */
final class Callers {

    private Callers() {
    }

    /** 실제 인가 어댑터(앱 롤 데이터소스 — {@code identity_link}·대상 사실은 RLS 아래에서 읽는다). */
    static AuthorizationPort authz(Clock clock) {
        PostgresHarness db = PostgresHarness.get();
        TenantJdbcGateway gateway = new TenantJdbcGateway(db.appDataSource());
        return new IdentityLinkAuthorization(new IdentityLinkRepository(gateway), new AuthzFactsRepository(gateway), new AuditLogRepository(gateway),
                new TenantTransactionTemplate(new TenantSessionBinder(db.appDataSource())), clock);
    }

    /**
     * 같은 주체의 운영자 CLI 대리 실행(감사 역할 OPERATOR, 범위 검사 없음) — 표가 막지 않으므로 업무 규칙의 역할·담당 검사({@code identity_link})가
     * 그대로 드러난다.
     */
    static Caller cli(TenantId tenant, Actor actor) {
        return Caller.cli(tenant, actor.subject());
    }

    static Caller of(TenantId tenant, Actor actor) {
        return switch (actor.role()) {
            case "AGENT", "MANAGER", "COMPLIANCE" -> Caller.api(tenant, actor.subject());
            default -> Caller.cli(tenant, actor.subject());
        };
    }
}
