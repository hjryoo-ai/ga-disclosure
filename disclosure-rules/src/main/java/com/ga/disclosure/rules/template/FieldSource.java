package com.ga.disclosure.rules.template;

/**
 * 서식 항목 값의 출처. 워크플로가 값을 어디서 채울지 분기하는 닫힌 어휘다(Phase 1 지시문 §6):
 * 카탈로그 기본값, 엔진 스냅샷, 설계사 입력, 시스템 계산. 어떤 항목이 어느 출처인지는 서식 데이터다.
 */
public enum FieldSource {
    CATALOG,
    ENGINE,
    AGENT,
    SYSTEM
}
