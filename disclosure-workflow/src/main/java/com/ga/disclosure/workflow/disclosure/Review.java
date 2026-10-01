package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.RuleVersionId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 관리자 예외 승인 1건(V6 {@code review}, append-only). 승인은 (확인서, 규칙, 실패 대상의 {@code SHA-256(JCS)})에 귀속되고(3A 계획 Q3),
 * 3B부터 승인 당시 확인서에 고정돼 있던 룰 버전 2종에도 귀속된다(3A 수용심사 §3-8, V7 — DB가 GD081로 부모의 고정 버전과 같은지 강제).
 * 대상이 바뀌거나 재기준으로 고정 버전이 바뀌면 효력이 없다 — 삭제가 아니라 귀속으로 무효가 된다. 봉인 조건이 {@link SealGate}로 소비한다.
 * 사유는 관리자 입력이며 감사 detail에는 싣지 않는다.
 *
 * @param tenantRuleVersionIdOrNull 승인 당시 고정된 사규 버전, 사규가 없었으면 {@code null}
 */
public record Review(UUID reviewId, DisclosureId disclosureId, String ruleId, String subjectHash, RuleVersionId ruleVersionId,
                     RuleVersionId tenantRuleVersionIdOrNull, String approvedBy, String approvedRole, Instant approvedAt, String reason) {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public Review {
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(disclosureId, "disclosureId");
        Objects.requireNonNull(ruleId, "ruleId");
        if (subjectHash == null || !HASH.matcher(subjectHash).matches()) {
            throw new IllegalArgumentException("subject hash must be 64 lowercase hex");
        }
        Objects.requireNonNull(ruleVersionId, "ruleVersionId");
        Objects.requireNonNull(approvedBy, "approvedBy");
        Objects.requireNonNull(approvedRole, "approvedRole");
        Objects.requireNonNull(approvedAt, "approvedAt");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an exception approval needs a reason");
        }
    }

    public Optional<RuleVersionId> tenantRuleVersionId() {
        return Optional.ofNullable(tenantRuleVersionIdOrNull);
    }

    /** 이 승인이 (규칙, 대상 해시)의 실패를 지금 고정된 룰 버전 아래에서 덮는가. */
    public boolean covers(String rule, String hash, RuleVersionId pinnedRule, Optional<RuleVersionId> pinnedTenantRule) {
        return ruleId.equals(rule) && subjectHash.equals(hash) && ruleVersionId.equals(pinnedRule)
                && tenantRuleVersionId().equals(pinnedTenantRule);
    }
}
