package com.ga.disclosure.rules.bundle;

import com.ga.disclosure.domain.vo.RuleVersionId;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 규제(GLOBAL) 룰 번들.
 *
 * @param supersedes 이 룰이 대체하는 선행 룰(없으면 {@code null}). 배포 시 선행 룰의 {@code apply_to}를 이 룰의 {@code applyFrom}으로 닫는다.
 */
public record RuleBundle(
        String bundleId,
        RuleVersionId ruleVersionId,
        LocalDate applyFrom,
        LocalDate applyTo,
        RuleVersionId supersedes,
        JsonNode body,
        String bodyHash) implements Bundle {

    public RuleBundle {
        Objects.requireNonNull(bundleId, "bundleId");
        Objects.requireNonNull(ruleVersionId, "ruleVersionId");
        Objects.requireNonNull(applyFrom, "applyFrom");
        Objects.requireNonNull(bodyHash, "bodyHash");
        body = Objects.requireNonNull(body, "body").deepCopy();
    }

    @Override
    public JsonNode body() {
        return body.deepCopy();
    }

    @Override
    public BundleKind kind() {
        return BundleKind.RULE;
    }
}
