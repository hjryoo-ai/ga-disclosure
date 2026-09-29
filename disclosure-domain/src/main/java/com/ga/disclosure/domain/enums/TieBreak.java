package com.ga.disclosure.domain.enums;

/**
 * 엔진 응답의 동점 처리(설계서 §4.1). 워크플로는 이 값에 따라 순위 검사식을 고른다(§6.3 (ii)) — 코드가 분기하는 닫힌 어휘.
 * 어떤 값을 허용할지는 룰 데이터({@code allowedTieBreaks})다.
 * <ul>
 *   <li>{@link #SHARED_RANK}: 경쟁 순위(1-2-2-4), 같은 순위 항목은 전부 {@code tie=true}</li>
 *   <li>{@link #STRICT}: 정책의 2차 기준으로 분리, OK 항목 m개의 순위가 1..m 순열</li>
 * </ul>
 */
public enum TieBreak {
    SHARED_RANK,
    STRICT
}
