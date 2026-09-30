/**
 * 엔진 등급·순위 클라이언트(설계서 §4.1, Phase 3A). 전송({@link com.ga.disclosure.infra.engine.EngineTransport}) → 계약 스키마 검증
 * ({@link com.ga.disclosure.infra.engine.EngineContract}) → 도메인 매핑({@code infra.json}) → {@code GradeConsistencyCheck}. 어느 단계든
 * 실패하면 스냅샷을 만들지 않는다. 비율({@code ratioToAvg})은 {@code RatioLabel}로만 흐른다 — 숫자로 바꾸거나 비교하지 않는다.
 */
package com.ga.disclosure.infra.engine;
