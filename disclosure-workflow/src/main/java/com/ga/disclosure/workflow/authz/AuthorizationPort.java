package com.ga.disclosure.workflow.authz;

import com.ga.disclosure.workflow.Actor;

/**
 * 인가 진입점(5 수용심사 결정 1). 유스케이스가 자기 트랜잭션 안에서(RLS 바인딩 뒤) 부른다. 허가면 감사에 적을 행위자(허가를 준 역할)를 돌려주고,
 * 아니면 {@link AuthorizationDenied} 하나를 던진다 — 권한 없음과 없는 대상은 같은 예외다. 거부 감사({@code AUTHZ_DENIED})는 어댑터가 별도
 * 트랜잭션에 남긴다(유스케이스 트랜잭션은 롤백된다).
 */
public interface AuthorizationPort {

    Actor require(Caller caller, Action action, Target target);

    /**
     * 목록 인가(대상 없음): 행위를 허가하는 첫 역할과 그 범위를 목록 조건({@link ListScope})으로 돌려준다. 범위에 필요한 연결(조직·설계사)이 없는 역할은
     * 건너뛴다. 아무 역할도 없으면 {@link AuthorizationDenied}(거부 감사는 {@link #require}와 같다).
     */
    ListGrant requireList(Caller caller, Action action);
}
