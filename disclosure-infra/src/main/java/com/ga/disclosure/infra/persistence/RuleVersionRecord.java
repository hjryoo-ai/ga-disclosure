package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * {@code rule_version} 행. 룰 본문은 데이터(JSON 원문)이며 해석은 Phase 1 룰 해석기가 한다.
 *
 * @param applyTo    배타 끝(구간 {@code [applyFrom, applyTo)}), {@code null}이면 무기한
 * @param approvedBy 승인자 subject(승인 전 {@code null})
 * @param approvedAt 승인 시각(승인 전 {@code null})
 * @param bodyJson   부록 D 구조 JSON 원문({@code contracts/rules/v1/rule-version.schema.json})
 */
public record RuleVersionRecord(
        RuleVersionId ruleVersionId,
        String scope,
        LocalDate applyFrom,
        LocalDate applyTo,
        RuleStatus status,
        String approvedBy,
        Instant approvedAt,
        String bodyJson) {

    public RuleVersionRecord {
        Objects.requireNonNull(ruleVersionId, "ruleVersionId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(applyFrom, "applyFrom");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(bodyJson, "bodyJson");
        if (applyTo != null && !applyTo.isAfter(applyFrom)) {
            throw new IllegalArgumentException("applyTo must be after applyFrom");
        }
    }
}
