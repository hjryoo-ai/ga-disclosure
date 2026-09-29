package com.ga.disclosure.domain.enums;

/**
 * 룰 범위. GLOBAL(규제)은 번들 복제본, TENANT(사규)는 GLOBAL이 {@code tenantOverridable}로 열어 둔 키만 덮어쓴다(설계서 §6.2).
 * 해석기가 두 범위를 각각 단건 해석하므로 코드가 분기하는 닫힌 어휘다.
 */
public enum RuleScope {
    GLOBAL,
    TENANT
}
