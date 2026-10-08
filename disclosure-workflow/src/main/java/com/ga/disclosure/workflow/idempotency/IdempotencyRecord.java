package com.ga.disclosure.workflow.idempotency;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code idempotency_key} 한 행. 완료 행만 응답 세 칸({@code responseStatus}·{@code responseRef}·{@code responseHash})이 있다.
 *
 * @param responseRef 영수증 튜플(JCS 텍스트) — {@code {"body": …, "location"?: …}}
 */
public record IdempotencyRecord(String requestHash, int claimSeq, Instant claimedAt, Instant expiresAt, Optional<Integer> responseStatus,
                                Optional<String> responseRef, Optional<String> responseHash) {

    public IdempotencyRecord {
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (responseStatus.isPresent() != responseRef.isPresent() || responseStatus.isPresent() != responseHash.isPresent()) {
            throw new IllegalArgumentException("a completed key has status, ref and hash together");
        }
    }

    public boolean completed() {
        return responseStatus.isPresent();
    }
}
