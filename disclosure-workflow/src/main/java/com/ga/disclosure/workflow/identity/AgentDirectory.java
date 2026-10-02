package com.ga.disclosure.workflow.identity;

import com.ga.platform.core.tenant.AgentId;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 행위자 → 설계사·역할·조직 해석 포트(V1 {@code identity_link}, CLAUDE.md 절대 규칙 5 — {@code agent_id}·역할·조직은 토큰 클레임이 아니라
 * 여기서 결정한다). 바인딩된 테넌트의 트랜잭션 안에서 부른다. 확인서의 {@code agent_id}는 작성 행위자를 이 포트로 해석한 값이고(Phase 4 — 3A는
 * 행위자 subject를 그대로 썼다), 설계사 서명은 해석한 {@code agent_id}가 확인서의 것과 같을 때만 허용된다(4 계획 §2.1).
 */
public interface AgentDirectory {

    Optional<LinkedIdentity> find(String subject);

    /** {@code identity_link} 행 1개. */
    record LinkedIdentity(String subject, AgentId agentId, Set<String> roles, String orgPath) {
        public LinkedIdentity {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(agentId, "agentId");
            roles = Set.copyOf(roles);
            Objects.requireNonNull(orgPath, "orgPath");
        }

        public boolean hasRole(String role) {
            return roles.contains(role);
        }
    }
}
