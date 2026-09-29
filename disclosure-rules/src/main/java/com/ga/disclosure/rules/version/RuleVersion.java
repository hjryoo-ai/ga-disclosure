package com.ga.disclosure.rules.version;

import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 룰 버전 1건(설계서 §5 {@code rule_version}). {@code body}는 부록 D 구조의 JSON이며 이 레코드는 해석하지 않는다 — 해석은
 * {@link com.ga.disclosure.rules.resolve.RuleResolver}의 몫이다. 적용 구간은 반개구간 {@code [applyFrom, applyTo)}.
 *
 * @param sourceBundleId GLOBAL이면 출처 번들 ID(예: {@code DISC-2026-07@3f2a9c0b1d4e}), TENANT면 {@code null}
 * @param bundleHash     GLOBAL이면 {@code SHA-256(JCS(body))}, TENANT면 {@code null}
 */
public record RuleVersion(
        RuleVersionId id,
        RuleScope scope,
        LocalDate applyFrom,
        LocalDate applyTo,
        RuleStatus status,
        String approvedBy,
        Instant approvedAt,
        JsonNode body,
        String sourceBundleId,
        String bundleHash) {

    public RuleVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(applyFrom, "applyFrom");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(body, "body");
        if (!body.isObject()) {
            throw new IllegalArgumentException("rule body must be a JSON object: " + id);
        }
        if (applyTo != null && !applyTo.isAfter(applyFrom)) {
            throw new IllegalArgumentException("applyTo must be after applyFrom: " + id);
        }
        boolean bundled = sourceBundleId != null && bundleHash != null;
        if ((scope == RuleScope.GLOBAL) != bundled || (sourceBundleId == null) != (bundleHash == null)) {
            throw new IllegalArgumentException("GLOBAL rules carry bundle provenance and TENANT rules do not: " + id);
        }
        body = body.deepCopy();
    }

    /** 방어적 복사본. */
    @Override
    public JsonNode body() {
        return body.deepCopy();
    }

    /** {@code date}가 적용 구간 {@code [applyFrom, applyTo)} 안인가. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(applyFrom) && (applyTo == null || date.isBefore(applyTo));
    }
}
