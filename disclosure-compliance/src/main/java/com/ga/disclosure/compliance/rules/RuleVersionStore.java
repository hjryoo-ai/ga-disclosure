package com.ga.disclosure.compliance.rules;

import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.version.RuleVersion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 룰 거버넌스가 쓰는 룰 버전 저장 포트(infra 어댑터가 구현, 바인딩된 테넌트 범위). 불변 규칙은 DB 트리거(V4)가 최종 강제한다 —
 * 이 포트에는 GLOBAL 본문을 바꾸는 연산 자체가 없다.
 */
public interface RuleVersionStore {

    Optional<RuleVersion> find(RuleVersionId id);

    void insert(RuleVersion rule);

    /** {@code apply_to}가 NULL일 때만 쓴다. 갱신 행 수. */
    int closeApplyTo(RuleVersionId id, LocalDate applyTo);

    /** DRAFT → APPROVED와 승인 기록. 갱신 행 수. */
    int approve(RuleVersionId id, String approvedBy, Instant approvedAt);

    /** {@code from} 상태일 때만 {@code to}로. 갱신 행 수. */
    int transition(RuleVersionId id, RuleStatus from, RuleStatus to);

    List<RuleVersion> findByStatus(RuleStatus status);

    /** GLOBAL 복제본 전부(상태 무관). */
    List<RuleVersion> findGlobalReplicas();
}
