package com.ga.platform.spring.identity;

import com.ga.platform.core.tenant.AgentId;

import java.util.Optional;
import java.util.Set;

/**
 * OIDC subject → {@code identity_link} 조회 → 설계사·역할·조직.
 *
 * <p>{@code agent_id}·역할·조직은 토큰 클레임이 아니라 이 조회 결과로만 결정한다(CLAUDE.md 절대 규칙 5).
 * 테넌트는 호출 시점의 {@code TenantContext}에서 온다. 구현과 요청 필터는 Phase 6(API·인가)에서 작성한다.
 */
public interface IdentityResolver {

    /** 연결된 신원이 없으면 빈 값(인증은 됐으나 이 테넌트의 사용자가 아님). */
    Optional<ResolvedIdentity> resolve(String oidcSubject);

    /**
     * @param agentId 설계사(관리자) 식별자
     * @param roles   역할 코드(데이터, 열거형 아님)
     * @param orgPath 조직 경로(관리자 조회 범위는 접두 일치)
     */
    record ResolvedIdentity(AgentId agentId, Set<String> roles, String orgPath) {
        public ResolvedIdentity {
            roles = Set.copyOf(roles);
            if (orgPath == null || orgPath.isBlank()) {
                throw new IllegalArgumentException("orgPath required");
            }
        }
    }
}
