package com.ga.disclosure.workflow.authz;

import com.ga.disclosure.workflow.Actor;

/**
 * 인가 진입점(5 수용심사 결정 1). 유스케이스가 자기 트랜잭션 안에서(RLS 바인딩 뒤) 부른다. 허가면 감사에 적을 행위자(허가를 준 역할)를 돌려주고,
 * 아니면 {@link AuthorizationDenied} 하나를 던진다 — 권한 없음과 없는 대상은 같은 예외다. 거부 감사({@code AUTHZ_DENIED})는 어댑터가 별도
 * 트랜잭션에 남긴다(유스케이스 트랜잭션은 롤백된다).
 */
public interface AuthorizationPort {

    Actor require(Caller caller, Action action, Target target);
}
