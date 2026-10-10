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

    /** 확인서 하나에 걸린 플래그 전부(열림·닫힘), 열린 시각 순 — {@code audience}가 {@link Audience#AGENT}면 열릴 때 설계사 가시로 고정된 행만. */
    List<Listed> forDisclosure(DisclosureId disclosureId, Audience audience);

    /**
     * 목록: 최근에 열린 순(열린 시각 내림차순, 같은 시각은 ID 내림차순). 범위 조건은 대상 확인서의 사실로 건다 — 조직·설계사 범위에서는 확인서가 없는 플래그
     * (테넌트 수준 {@code CHAIN_BROKEN}·룰 플래그)는 없는 행이다.
     */
    List<Listed> page(ListScope scope, Filter filter, Optional<Position> after, int limit, Audience audience);

    /** 플래그 하나의 상태(배정·해소 유스케이스 — 범위 판정은 인가가 먼저 한다). 없으면 빈 값. */
    Optional<State> state(UUID flagId);

    /**
     * 목록 필터(6B 계획 §7): 상태·유형·담당 역할·기한({@code dueBefore} 미만, 기한 없는 플래그는 빠진다). 응답 필드는 6A 수용심사 §2 ①의 요약 그대로다.
     */
    record Filter(Optional<FlagStatus> status, Optional<String> type, Optional<String> assignedRole, Optional<Instant> dueBefore) {
        public Filter {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(assignedRole, "assignedRole");
            Objects.requireNonNull(dueBefore, "dueBefore");
        }

        public static Filter none() {
            return new Filter(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** 배정·해소 판정에 쓰는 사실. */
    record State(UUID flagId, String type, Optional<DisclosureId> disclosureId, Instant raisedAt, String assignedRole, boolean open) {
        public State {
            Objects.requireNonNull(flagId, "flagId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(disclosureId, "disclosureId");
            Objects.requireNonNull(raisedAt, "raisedAt");
            Objects.requireNonNull(assignedRole, "assignedRole");
        }
    }

    /**
     * 읽는 쪽(Phase 7 승인 Q1): 설계사는 {@code compliance_flag.visible_to_agent}(열릴 때 룰 {@code complianceQueue.types.*.visibleToAgent}에서 복사)가
     * 참인 행만 — 걸러 내는 곳은 저장소 조건 하나다(화면은 거르지 않는다).
     */
    enum Audience {
        STAFF, AGENT
    }

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
