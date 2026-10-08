package com.ga.disclosure.workflow.flag;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.authz.ListScope;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 준법 플래그 읽기(설계서 §5 {@code compliance_flag}). 상태를 바꾸지 않는다. */
public interface FlagLookup {

    /** 확인서 하나에 걸린 플래그 전부(열림·닫힘), 열린 시각 순. */
    List<Listed> forDisclosure(DisclosureId disclosureId);

    /**
     * 목록: 최근에 열린 순(열린 시각 내림차순, 같은 시각은 ID 내림차순). 범위 조건은 대상 확인서의 사실로 건다 — 조직·설계사 범위에서는 확인서가 없는 플래그
     * (테넌트 수준 {@code CHAIN_BROKEN}·룰 플래그)는 없는 행이다.
     */
    List<Listed> page(ListScope scope, Optional<FlagStatus> status, Optional<String> type, Optional<Position> after, int limit);

    enum FlagStatus {
        OPEN, RESOLVED
    }

    record Position(Instant raisedAt, UUID flagId) {
        public Position {
            Objects.requireNonNull(raisedAt, "raisedAt");
            Objects.requireNonNull(flagId, "flagId");
        }
    }

    /** 플래그 한 건 요약 — 대상 확인서는 없을 수 있고, 번호는 봉인 뒤에만 있다. */
    record Listed(UUID flagId, String type, FlagStatus status, Instant raisedAt, Optional<DisclosureId> disclosureId, Optional<String> disclosureNo) {
        public Listed {
            Objects.requireNonNull(flagId, "flagId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(raisedAt, "raisedAt");
            Objects.requireNonNull(disclosureId, "disclosureId");
            Objects.requireNonNull(disclosureNo, "disclosureNo");
        }
    }
}
