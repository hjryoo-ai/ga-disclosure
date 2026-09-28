package com.ga.disclosure.rules.grade;

/**
 * 엔진 등급·순위 스냅샷의 정합성 검증(설계서 §6.3: 집합 일치·순위 연속·순위-등급 단조·정책 버전 허용 목록). <b>Phase 3에서 구현한다.</b>
 *
 * <p>아키텍처 테스트의 허용 목록에 오른 유일한 클래스다 — {@code GradeSnapshotItem}을 원소로 순서를 매기는
 * ({@code Comparator}·{@code sorted}·{@code max}·{@code min}) 코드는 여기에만 둘 수 있으며, 그때도 엔진이 준 정수
 * {@code rankInSet}·{@code gradeOrdinal}만 쓴다. {@code ratioToAvg}는 여기서도 읽지 않는다(CLAUDE.md 절대 규칙 1).
 */
public final class GradeConsistencyCheck {

    private GradeConsistencyCheck() {
    }
}
