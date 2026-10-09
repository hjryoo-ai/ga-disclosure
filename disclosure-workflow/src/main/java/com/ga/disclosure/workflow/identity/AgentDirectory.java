package com.ga.disclosure.workflow.identity;

import com.ga.platform.core.tenant.AgentId;
import com.ga.platform.core.tenant.OrgPath;

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

    /**
     * {@code identity_link} 행 1개. 설계사가 아닌 주체(COMPLIANCE·SCHEDULER·FEED_CONSUMER)는 {@code agent_id}가 없고, 서비스 주체는 조직도
     * 없다(V12, 6A 승인 Q2 — AGENT ⇒ agent_id, AGENT·MANAGER ⇒ 조직 경로는 DB CHECK). {@code feedSources}는 계약 피드 주체가 보낼 수 있는 출처(V18 —
     * 그 밖의 주체는 비어 있다).
     */
    record LinkedIdentity(String subject, Optional<AgentId> agentId, Set<String> roles, Optional<OrgPath> orgPath, Set<String> feedSources) {
        public LinkedIdentity {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(agentId, "agentId");
            roles = Set.copyOf(roles);
            Objects.requireNonNull(orgPath, "orgPath");
            feedSources = Set.copyOf(feedSources);
        }

        public LinkedIdentity(String subject, Optional<AgentId> agentId, Set<String> roles, Optional<OrgPath> orgPath) {
            this(subject, agentId, roles, orgPath, Set.of());
        }

        public boolean hasRole(String role) {
            return roles.contains(role);
        }
    }
}
