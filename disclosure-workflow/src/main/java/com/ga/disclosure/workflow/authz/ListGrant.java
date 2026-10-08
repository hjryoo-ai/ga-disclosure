package com.ga.disclosure.workflow.authz;

import com.ga.disclosure.workflow.Actor;

import java.util.Objects;

/** 목록 인가 결과: 감사 행위자(허가를 준 역할)와 목록 범위. */
public record ListGrant(Actor actor, ListScope scope) {

    public ListGrant {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(scope, "scope");
    }
}
