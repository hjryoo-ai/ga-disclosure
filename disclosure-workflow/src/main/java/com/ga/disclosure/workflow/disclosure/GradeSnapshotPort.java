package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.vo.SnapshotId;
import com.ga.platform.core.tenant.TenantId;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 엔진 등급·순위 포트(설계서 §4.1, infra 어댑터). 응답은 <b>수신 즉시</b> 계약 스키마로 검증하고(oneOf 분기 포함) 도메인 타입으로 옮긴 뒤
 * {@code GradeConsistencyCheck}로 정합성을 검사한다. 둘 중 하나라도 실패하면 스냅샷을 만들지 않고 {@link Fetch.Rejected}(위반 목록)다.
 * 연결 실패·타임아웃·엔진 오류 응답(4xx·5xx)은 {@link EngineUnavailableException}. 트랜잭션 밖에서 호출한다(3A 계획 Q6).
 */
public interface GradeSnapshotPort {

    Fetch request(TenantId tenant, EngineRequest request, Allowance allowance);

    /** 발급된 스냅샷 재조회(바이트 동일 약속, §4.1). 같은 검증을 거친다. */
    Fetch refetch(TenantId tenant, SnapshotId snapshotId, EngineRequest request, Allowance allowance);

    /** 확인서에 고정된 룰이 허용하는 정책 버전·동점 처리(룰 데이터, §6.3 (iv)). */
    record Allowance(Set<String> gradingPolicies, Set<String> rankingPolicies, Set<TieBreak> tieBreaks) {
        public Allowance {
            gradingPolicies = Set.copyOf(gradingPolicies);
            rankingPolicies = Set.copyOf(rankingPolicies);
            tieBreaks = Set.copyOf(tieBreaks);
        }
    }

    sealed interface Fetch {
        record Accepted(EngineSnapshot snapshot) implements Fetch {
            public Accepted {
                Objects.requireNonNull(snapshot, "snapshot");
            }
        }

        /** 스키마 위반(접두 {@code schema:}) 또는 정합성 위반(§6.3 (i)~(v)). 스냅샷 ID는 응답에서 읽을 수 있었을 때만. */
        record Rejected(String snapshotIdOrNull, List<String> violations) implements Fetch {
            public Rejected {
                violations = List.copyOf(violations);
                if (violations.isEmpty()) {
                    throw new IllegalArgumentException("a rejected response names its violations");
                }
            }
        }
    }
}
