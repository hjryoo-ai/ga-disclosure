package com.ga.disclosure.api.dto;

import java.util.List;

/** 징구율 조회 응답: 정의 표기({@code INTERNAL_METRIC_NO_REGULATORY_DEFINITION}, "내부 지표 — 규제 정의 없음")와 행(달·조직 순, 쪽 나눔 없음 — 기간 상한). */
public record CollectionRateList(String definition, String definitionText, List<CollectionRateView> items) {
}
