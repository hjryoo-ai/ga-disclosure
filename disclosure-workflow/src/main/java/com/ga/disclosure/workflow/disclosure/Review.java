package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 관리자 예외 승인 1건(V6 {@code review}, append-only). 승인은 (확인서, 규칙, 실패 대상의 {@code SHA-256(JCS)})에 귀속된다 —
 * 대상이 바뀌면 효력이 없고 같은 대상이면 재산출 뒤에도 유지된다(3A 계획 Q3). 3B 봉인 조건이 {@link SealGate}로 소비한다.
 * 사유는 관리자 입력이며 감사 detail에는 싣지 않는다.
 */
public record Review(UUID reviewId, DisclosureId disclosureId, String ruleId, String subjectHash, String approvedBy, String approvedRole,
                     Instant approvedAt, String reason) {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public Review {
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(disclosureId, "disclosureId");
        Objects.requireNonNull(ruleId, "ruleId");
        if (subjectHash == null || !HASH.matcher(subjectHash).matches()) {
            throw new IllegalArgumentException("subject hash must be 64 lowercase hex");
        }
        Objects.requireNonNull(approvedBy, "approvedBy");
        Objects.requireNonNull(approvedRole, "approvedRole");
        Objects.requireNonNull(approvedAt, "approvedAt");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an exception approval needs a reason");
        }
    }

    public boolean covers(String rule, String hash) {
        return ruleId.equals(rule) && subjectHash.equals(hash);
    }
}
