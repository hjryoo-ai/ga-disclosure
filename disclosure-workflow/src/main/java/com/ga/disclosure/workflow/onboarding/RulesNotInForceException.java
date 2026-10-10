package com.ga.disclosure.workflow.onboarding;

/**
 * 오늘(KST) 시행 중인 GLOBAL 룰이 없는 테넌트의 룰 읽기(Phase 8 — 룰 어휘). API는 쓰기의 503 {@code TENANT_RULES_NOT_ACTIVE}와 같은 응답으로 바꾼다.
 * 문장에 테넌트·날짜를 넣지 않는다.
 */
public final class RulesNotInForceException extends RuntimeException {

    public RulesNotInForceException() {
        super("no GLOBAL rule is in force for this tenant today");
    }
}
