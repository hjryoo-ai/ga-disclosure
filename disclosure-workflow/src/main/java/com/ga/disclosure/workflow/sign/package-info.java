/**
 * 서명 포트·기록 형식(Phase 4, 설계서 §6.5): 고객 서명 세션({@code sign_session})과 서명 레코드({@code signature})의 저장소 포트, 서명 수집 입력,
 * 통지 포트. 순수 규칙(세션 상태표·토큰·본인확인 정책·대리 서명 탐지·게이트·보존 앵커)은 {@code disclosure-sign}에, 트랜잭션 유스케이스는
 * {@code workflow.disclosure}의 {@code SignSessionService}·{@code SignService}에 있다(확인서 로더·명령 실행기를 함께 쓴다).
 */
package com.ga.disclosure.workflow.sign;
