package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.identity.AgentDirectory;

/**
 * 업무 규칙이 요구하는 역할(룰 {@code exceptionApproval.role} 등)의 판정 — 행위자의 감사 역할이 아니라 {@code identity_link}의 역할로 본다
 * (6A, 절대 규칙 5). CLI의 감사 역할은 언제나 OPERATOR이지만(승인 Q9) {@code --operator}로 준 주체가 연결에서 그 역할을 가지면 대리 실행이
 * 성립한다. 바인딩된 테넌트의 트랜잭션 안에서 부른다.
 */
final class BusinessRoles {

    private BusinessRoles() {
    }

    static boolean holds(AgentDirectory agents, Actor actor, String role) {
        return agents.find(actor.subject()).map(l -> l.hasRole(role)).orElse(false);
    }
}
