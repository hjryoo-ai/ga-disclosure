package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.identity.AgentDirectory;
import com.ga.platform.core.tenant.AgentId;
import com.ga.platform.core.tenant.OrgPath;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.HashMap;
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

    /**
     * 연결이 없으면 넣는다(있으면 그대로 — 1 = 새로 넣음). 설계사가 아닌 주체는 {@code agentIdOrNull}이 NULL, 서비스 주체는 조직도 NULL(V12
     * CHECK가 역할과의 정합을 강제한다).
     */
    public int linkIfAbsent(String subject, String agentIdOrNull, java.util.List<String> roles, String orgPathOrNull) {
        Map<String, Object> p = new HashMap<>();
        p.put("subject", subject);
        p.put("agentId", agentIdOrNull == null ? null : AgentId.of(agentIdOrNull).value());
        p.put("roles", roles.toArray(String[]::new));
        p.put("orgPath", orgPathOrNull == null ? null : OrgPath.of(orgPathOrNull).value());
        return update("""
                INSERT INTO identity_link (tenant_id, subject, agent_id, roles, org_path)
                VALUES (:tenantId, :subject, :agentId, :roles, :orgPath)
                ON CONFLICT (tenant_id, subject) DO NOTHING
                """, p);
    }

    @Override
    public Optional<LinkedIdentity> find(String subject) {
        return queryAtMostOne("""
                SELECT subject, agent_id, roles, org_path
                  FROM identity_link
                 WHERE tenant_id = :tenantId
                   AND subject = :subject
                """, Map.of("subject", subject), (rs, n) -> new LinkedIdentity(rs.getString("subject"),
                Optional.ofNullable(rs.getString("agent_id")).map(AgentId::of),
                new HashSet<>(Arrays.asList((String[]) rs.getArray("roles").getArray())),
                Optional.ofNullable(rs.getString("org_path")).map(OrgPath::of)));
    }
}
