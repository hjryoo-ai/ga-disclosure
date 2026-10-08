package com.ga.disclosure.workflow.authz;

import com.ga.platform.core.tenant.AgentId;
import com.ga.platform.core.tenant.OrgPath;

import java.util.Objects;

/**
 * 목록 범위(6A 계획 §4.1 — "범위로 걸러진 목록"). 대상 하나를 판정하는 {@link ScopePolicy.Scope}를 목록 조건으로 옮긴 것이다: {@code TENANT}·{@code ANY}
 * → 테넌트 전체, {@code ORG} → 작성 시점 조직이 주체 조직의 세그먼트 접두 아래({@code /HQ}는 {@code /HQX}를 덮지 않는다), {@code OWN} → 작성 설계사 =
 * 주체의 설계사. 저장소가 이 조건을 SQL로 건다 — 걸러진 행은 "없는" 행이다.
 */
public sealed interface ListScope {

    record WholeTenant() implements ListScope {
    }

    record OwnedBy(AgentId agent) implements ListScope {
        public OwnedBy {
            Objects.requireNonNull(agent, "agent");
        }
    }

    record UnderOrg(OrgPath org) implements ListScope {
        public UnderOrg {
            Objects.requireNonNull(org, "org");
        }
    }
}
