/**
 * 징구율(6B 지시문 §4, 계획 §5): 월별 스냅샷 작업({@code COLLECTION_RATE_SNAPSHOT})과 조회({@code COLLECTION_RATE_READ}). 산식은 룰 모듈의 순수 계산
 * ({@code rules.metric.CollectionRates})이고 룰 {@code collectionRate.formula}가 고른다. 내부 지표 — 규제 정의 없음(설계서 §14 #17).
 */
package com.ga.disclosure.workflow.rate;
