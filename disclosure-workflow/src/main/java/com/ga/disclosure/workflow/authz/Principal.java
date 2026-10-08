package com.ga.disclosure.workflow.authz;

import com.ga.platform.core.tenant.AgentId;
import com.ga.platform.core.tenant.OrgPath;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** {@code identity_link} 해석 결과(어댑터 안에서만 만든다 — 토큰 클레임에서 만들 길이 없다). */
public record Principal(String subject, Set<Role> roles, Optional<AgentId> agentId, Optional<OrgPath> orgPath) {

    public Principal {
        Objects.requireNonNull(subject, "subject");
        roles = Set.copyOf(roles);
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(orgPath, "orgPath");
    }
}
