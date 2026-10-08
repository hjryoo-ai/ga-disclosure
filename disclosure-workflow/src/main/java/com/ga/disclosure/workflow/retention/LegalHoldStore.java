package com.ga.disclosure.workflow.retention;

import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 법적 보류(V9 {@code legal_hold} — 설정 1회, 해제 1회, 삭제 없음 GD112, 대상당 활성 1건). */
public interface LegalHoldStore {

    void insert(Hold hold);

    /** PLACED → RELEASED 1회. 이미 해제됐거나 없으면 false. */
    boolean release(UUID holdId, String by, Instant at, String reasonCode);

    Optional<Hold> find(UUID holdId);

    Optional<Hold> activeFor(DisclosureId disclosure);

    Optional<Hold> activeFor(CustomerRef customer);

    /** 최근 설정 순(설정 시각 내림차순, 같은 시각은 ID 내림차순). {@code after}가 있으면 그 행 다음부터(키셋). */
    List<Hold> page(Optional<Position> after, int limit);

    record Position(Instant placedAt, UUID holdId) {
    }

    /** 대상은 확인서 또는 고객 중 하나. */
    record Hold(UUID holdId, DisclosureId disclosureOrNull, CustomerRef customerOrNull, String reasonCode, String reasonTextOrNull, String placedBy,
                Instant placedAt, String releasedByOrNull, Instant releasedAtOrNull, String releaseReasonCodeOrNull) {
        public Hold {
            if ((disclosureOrNull == null) == (customerOrNull == null)) {
                throw new IllegalArgumentException("a hold targets one disclosure or one customer");
            }
        }

        public boolean active() {
            return releasedAtOrNull == null;
        }
    }
}
