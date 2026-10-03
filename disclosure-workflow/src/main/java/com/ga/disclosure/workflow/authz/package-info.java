/**
 * 인가(6A 계획 §3, 5 수용심사 결정 1). 토큰에서 오는 것은 {@link com.ga.disclosure.workflow.authz.Caller}(테넌트·주체·채널)뿐이고 역할·설계사·
 * 조직은 {@code identity_link}에서 정한다(CLAUDE.md 절대 규칙 5). 유스케이스 진입점({@link com.ga.disclosure.workflow.authz.UseCaseEntry})은
 * 트랜잭션 안에서 {@link com.ga.disclosure.workflow.authz.AuthorizationPort#require}를 직접 부르고(ArchUnit), 범위 판정은 순수 함수
 * {@link com.ga.disclosure.workflow.authz.ScopePolicy}다(설계서 §9 {@code authz-matrix} 블록과 양방향 대조). Spring 무의존.
 */
package com.ga.disclosure.workflow.authz;
