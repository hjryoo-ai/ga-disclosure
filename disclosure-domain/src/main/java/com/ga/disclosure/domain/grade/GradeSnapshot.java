package com.ga.disclosure.domain.grade;

import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.vo.SnapshotId;

import java.util.List;
import java.util.Objects;

/**
 * 엔진 등급·순위 응답 스냅샷(설계서 §4.1). 헤더(정책 버전 2종·동점 처리)와 상품별 결과. 정합성 검증은
 * {@code disclosure-rules}의 {@code GradeConsistencyCheck}가 엔진이 준 정수만으로 한다.
 *
 * @param snapshotId              엔진 스냅샷 ID(재조회 키)
 * @param gradingPolicyVersionId  등급 임계치·모수·기간 정의 버전(엔진 데이터)
 * @param rankingPolicyVersionId  세트 내 순위·동점 규칙 버전(엔진 데이터)
 * @param tieBreak                동점 처리
 * @param items                   상품별 결과(엔진이 준 순서 그대로)
 */
public record GradeSnapshot(
        SnapshotId snapshotId,
        String gradingPolicyVersionId,
        String rankingPolicyVersionId,
        TieBreak tieBreak,
        List<GradeSnapshotItem> items) {

    public GradeSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(gradingPolicyVersionId, "gradingPolicyVersionId");
        Objects.requireNonNull(rankingPolicyVersionId, "rankingPolicyVersionId");
        Objects.requireNonNull(tieBreak, "tieBreak");
        items = List.copyOf(items);
    }
}
