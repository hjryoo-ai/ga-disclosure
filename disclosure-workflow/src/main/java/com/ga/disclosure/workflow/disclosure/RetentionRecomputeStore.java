package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 보존 재계산의 대상과 쓰기(6B 계획 §8). 쓰기는 "더 길 때만"(그리고 파기되지 않았을 때만) 하나뿐이다 — 단축 경로가 없다(GD094가 한 번 더 막는다). */
public interface RetentionRecomputeStore {

    /** 봉인 이후(번호·보존기한 있음)·미파기 확인서와 앵커 날짜의 재료. */
    record Target(DisclosureId id, String disclosureNo, LocalDate retentionUntil, Instant sealedAt, Optional<Instant> completedAt,
                  Optional<LocalDate> contractDate) {
        public Target {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(disclosureNo, "disclosureNo");
            Objects.requireNonNull(retentionUntil, "retentionUntil");
            Objects.requireNonNull(sealedAt, "sealedAt");
            Objects.requireNonNull(completedAt, "completedAt");
            Objects.requireNonNull(contractDate, "contractDate");
        }
    }

    /** 대상 한 쪽(확인서 ID 오름차순, {@code after} 다음부터 {@code limit}개). */
    List<Target> live(Optional<DisclosureId> after, int limit);

    /** 봉인 이후였고 파기된 확인서 수(보고서에 건수만). */
    int destroyed();

    /** {@code retention_until < until}이고 파기되지 않았을 때만 연장한다. 연장했으면 true. */
    boolean extendRetention(DisclosureId id, LocalDate until);
}
