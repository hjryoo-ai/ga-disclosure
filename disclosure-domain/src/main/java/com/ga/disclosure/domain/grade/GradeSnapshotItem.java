package com.ga.disclosure.domain.grade;

import com.ga.disclosure.domain.enums.GradeStatus;
import com.ga.disclosure.domain.vo.ProductKey;

import java.util.Objects;

/**
 * 엔진 등급·순위 스냅샷의 상품 1건(설계서 §4.1 {@code results[]}).
 *
 * <p>도메인은 등급 코드·개수·의미를 알지 못한다 — {@code gradeCode}/{@code gradeLabel}은 문자열, 순서는 엔진이 정책 데이터에서
 * 내려준 {@code gradeOrdinal}(수수료가 낮을수록 작은 정수)로만 표현된다. 순위-등급 단조성 재검증은 {@code rankInSet}·
 * {@code gradeOrdinal} 정수만으로 {@code disclosure-rules}의 {@code GradeConsistencyCheck}가 한다.
 * 이 타입은 {@link Comparable}이 아니며 정렬 대상이 되지 않는다(아키텍처 테스트).
 *
 * <p>{@link GradeStatus#UNAVAILABLE}(임시등록·미공시 등 산출 불가)이면 등급·라벨·비율은 {@code null}, 서수·순위는 0, 동점은 false이고
 * 엔진이 준 산출불가 사유({@code unavailableReason}, 불투명 문자열)가 있다 — 엔진 계약에서 이 필드들은 null이 아니라 부재다(§4.1).
 * {@link GradeStatus#OK}이면 전부 존재하고 서수·순위는 1 이상이며 사유는 {@code null}이다.
 *
 * @param productKey   상품키
 * @param status       산출 상태
 * @param gradeCode    등급 코드(엔진 정책 데이터, 예: {@code LOW})
 * @param gradeLabel   등급 라벨(인쇄용, 예: {@code 낮음})
 * @param gradeOrdinal 등급 서수(엔진 제공)
 * @param rankInSet    요청 세트 안의 순위(1순위가 가장 저렴)
 * @param tie          동점 여부(표기 규칙은 엔진 rankingPolicy 데이터)
 * @param ratioToAvg   평균 대비 비율(불투명 문자열)
 * @param unavailableReason 산출불가 사유(엔진 데이터 문자열, 예: {@code NO_RATE_DATA}). UNAVAILABLE일 때만
 */
public record GradeSnapshotItem(
        ProductKey productKey,
        GradeStatus status,
        String gradeCode,
        String gradeLabel,
        int gradeOrdinal,
        int rankInSet,
        boolean tie,
        RatioLabel ratioToAvg,
        String unavailableReason) {

    public GradeSnapshotItem {
        Objects.requireNonNull(productKey, "productKey");
        Objects.requireNonNull(status, "status");
        switch (status) {
            case OK -> {
                if (gradeCode == null || gradeCode.isBlank() || gradeLabel == null || gradeLabel.isBlank() || ratioToAvg == null) {
                    throw new IllegalArgumentException("OK item requires gradeCode, gradeLabel and ratioToAvg: " + productKey);
                }
                if (gradeOrdinal < 1 || rankInSet < 1) {
                    throw new IllegalArgumentException("OK item requires gradeOrdinal >= 1 and rankInSet >= 1: " + productKey);
                }
                if (unavailableReason != null) {
                    throw new IllegalArgumentException("OK item must not carry an unavailable reason: " + productKey);
                }
            }
            case UNAVAILABLE -> {
                if (gradeCode != null || gradeLabel != null || ratioToAvg != null || gradeOrdinal != 0 || rankInSet != 0 || tie) {
                    throw new IllegalArgumentException("UNAVAILABLE item must not carry grade, rank or ratio: " + productKey);
                }
                if (unavailableReason == null || unavailableReason.isBlank()) {
                    throw new IllegalArgumentException("UNAVAILABLE item requires the engine's reason: " + productKey);
                }
            }
        }
    }

    public static GradeSnapshotItem ok(ProductKey productKey, String gradeCode, String gradeLabel,
                                       int gradeOrdinal, int rankInSet, boolean tie, RatioLabel ratioToAvg) {
        return new GradeSnapshotItem(productKey, GradeStatus.OK, gradeCode, gradeLabel, gradeOrdinal, rankInSet, tie, ratioToAvg, null);
    }

    public static GradeSnapshotItem unavailable(ProductKey productKey, String reason) {
        return new GradeSnapshotItem(productKey, GradeStatus.UNAVAILABLE, null, null, 0, 0, false, null, reason);
    }

    public boolean isAvailable() {
        return status == GradeStatus.OK;
    }
}
