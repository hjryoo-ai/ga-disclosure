package com.ga.disclosure.workflow.authz;

import com.ga.platform.core.tenant.AgentId;
import com.ga.platform.core.tenant.OrgPath;

import java.util.Objects;
import java.util.Optional;

/** 범위 판정에 쓰는 대상의 사실. 없는 대상(다른 테넌트 포함)은 {@link Missing}이다. */
public sealed interface TargetFacts {

    /** 대상이 없다 — 권한 없음과 같은 404로 수렴한다. */
    record Missing() implements TargetFacts {
    }

    /** 대상 없는 행위이거나, 소유 범위가 없는 대상(보류·작업)이 있다. */
    record Tenant() implements TargetFacts {
    }

    /** 계약 피드의 출처(6B 중간 회신 ②) — 범위 {@code SOURCE}가 주체의 출처 목록과 대조한다. */
    record OfFeedSource(String source) implements TargetFacts {
        public OfFeedSource {
            Objects.requireNonNull(source, "source");
        }
    }

    /** 확인서(또는 세션이 가리키는 확인서): 작성 설계사와 작성 시점 조직(V12 이전 행은 조직 없음). */
    record OfDisclosure(AgentId agentId, Optional<OrgPath> orgPath) implements TargetFacts {
        public OfDisclosure {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(orgPath, "orgPath");
        }
    }
}
