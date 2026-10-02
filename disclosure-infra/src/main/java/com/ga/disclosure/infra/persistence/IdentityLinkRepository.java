package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.identity.AgentDirectory;
import com.ga.platform.core.tenant.AgentId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

/**
 * {@link AgentDirectory} 어댑터(V1 {@code identity_link}). 쓰기는 {@link #linkIfAbsent} 하나 — 데모·시드 전용(운영의 연결 관리는 테넌트 온보딩·
 * Phase 6). subject는 IdP 식별자이고 고객 개인정보가 아니다.
 */
@Repository
public class IdentityLinkRepository extends TenantScopedRepository implements AgentDirectory {

    public IdentityLinkRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    /** 연결이 없으면 넣는다(있으면 그대로 — 1 = 새로 넣음). */
    public int linkIfAbsent(String subject, String agentId, java.util.List<String> roles, String orgPath) {
        AgentId.of(agentId);
        return update("""
                INSERT INTO identity_link (tenant_id, subject, agent_id, roles, org_path)
                VALUES (:tenantId, :subject, :agentId, :roles, :orgPath)
                ON CONFLICT (tenant_id, subject) DO NOTHING
                """, Map.of("subject", subject, "agentId", agentId, "roles", roles.toArray(String[]::new), "orgPath", orgPath));
    }

    @Override
    public Optional<LinkedIdentity> find(String subject) {
        return queryAtMostOne("""
                SELECT subject, agent_id, roles, org_path
                  FROM identity_link
                 WHERE tenant_id = :tenantId
                   AND subject = :subject
                """, Map.of("subject", subject), (rs, n) -> new LinkedIdentity(rs.getString("subject"), AgentId.of(rs.getString("agent_id")),
                new HashSet<>(Arrays.asList((String[]) rs.getArray("roles").getArray())), rs.getString("org_path")));
    }
}
