package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.TargetFacts;
import com.ga.platform.core.tenant.AgentId;
import com.ga.platform.core.tenant.OrgPath;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

/**
 * 인가 범위 판정의 사실(6A 계획 §3.1): 대상 확인서의 작성 설계사·작성 시점 조직, 세션이 가리키는 확인서, 보류·작업의 존재. 전부 바인딩된 테넌트의
 * RLS 아래에서 읽는다 — 다른 테넌트의 대상은 {@link TargetFacts.Missing}(권한 없음과 같은 404, 5 수용심사 결정 1).
 */
@Repository
public class AuthzFactsRepository extends TenantScopedRepository {

    public AuthzFactsRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    public TargetFacts facts(Target target) {
        return switch (target) {
            case Target.None n -> new TargetFacts.Tenant();
            case Target.Disclosure d -> disclosure("""
                    SELECT agent_id, org_path
                      FROM disclosure
                     WHERE tenant_id = :tenantId
                       AND disclosure_id = :id
                    """, d.id().value());
            case Target.Session s -> disclosure("""
                    SELECT d.agent_id, d.org_path
                      FROM sign_session ss
                      JOIN disclosure d ON d.tenant_id = ss.tenant_id AND d.disclosure_id = ss.disclosure_id
                     WHERE ss.tenant_id = :tenantId
                       AND d.tenant_id = :tenantId
                       AND ss.session_id = :id
                    """, s.id());
            case Target.Hold h -> exists("""
                    SELECT 1 FROM legal_hold WHERE tenant_id = :tenantId AND hold_id = :id
                    """, h.id());
            case Target.Job j -> exists("""
                    SELECT 1 FROM async_job WHERE tenant_id = :tenantId AND job_id = :id
                    """, j.id());
            case Target.Flag f -> flag(f);
            case Target.FeedSource s -> new TargetFacts.OfFeedSource(s.source());
        };
    }

    /** 바인딩된 테넌트의 행이 있는가(거부 감사는 있는 테넌트에만 남긴다 — 위조 접두로 남의 이름의 행을 만들지 않는다). */
    public boolean tenantExists() {
        return queryAtMostOne("SELECT 1 FROM tenant WHERE tenant_id = :tenantId", Map.of(), (rs, n) -> Boolean.TRUE).isPresent();
    }

    private TargetFacts disclosure(String sql, Object id) {
        Optional<TargetFacts> found = queryAtMostOne(sql, Map.of("id", id), (rs, n) -> new TargetFacts.OfDisclosure(
                AgentId.of(rs.getString("agent_id")), Optional.ofNullable(rs.getString("org_path")).map(OrgPath::of)));
        return found.orElseGet(TargetFacts.Missing::new);
    }

    /** 확인서에 걸린 플래그는 그 확인서의 사실, 테넌트 수준 플래그는 소유 범위 없음(준법·운영자만 닿는다). */
    private TargetFacts flag(Target.Flag f) {
        Optional<Optional<java.util.UUID>> disclosure = queryAtMostOne("""
                SELECT disclosure_id FROM compliance_flag WHERE tenant_id = :tenantId AND flag_id = :id
                """, Map.of("id", f.id()), (rs, n) -> Optional.ofNullable(rs.getObject("disclosure_id", java.util.UUID.class)));
        if (disclosure.isEmpty()) {
            return new TargetFacts.Missing();
        }
        return disclosure.get().isEmpty() ? new TargetFacts.Tenant() : disclosure("""
                SELECT agent_id, org_path
                  FROM disclosure
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :id
                """, disclosure.get().get());
    }

    private TargetFacts exists(String sql, Object id) {
        return queryAtMostOne(sql, Map.of("id", id), (rs, n) -> (TargetFacts) new TargetFacts.Tenant()).orElseGet(TargetFacts.Missing::new);
    }
}
