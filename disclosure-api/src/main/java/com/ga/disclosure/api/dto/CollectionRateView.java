package com.ga.disclosure.api.dto;

/**
 * 징구율 스냅샷 한 행(6B 계획 §5·승인 §5): 기준월(yyyy-MM)·조직(테넌트 전체는 {@code /})·산식 ID·정의 표기·룰 버전·분모·분자·만분율(분모 0이면 null)·계산
 * 시각·입력 해시. 개인정보·번호 없음.
 */
public record CollectionRateView(String snapshotId, String periodMonth, String orgPath, String formula, String definition, String ruleVersionId,
                                 int denominator, int numerator, Integer rateBp, String computedAt, String inputsHash) {
}
