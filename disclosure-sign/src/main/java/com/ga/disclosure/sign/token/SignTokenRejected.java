package com.ga.disclosure.sign.token;

/**
 * 토큰으로 세션을 열 수 없다. 형식 오류·없는 테넌트·틀린 토큰·닫힌 세션이 <b>같은 타입</b>이다(4 계획 승인 B2 — 존재 누설 금지; 같은 응답·
 * 같은 지연은 Phase 6 공개 엔드포인트 완료 기준). 메시지에 토큰·테넌트를 넣지 않는다.
 */
public final class SignTokenRejected extends RuntimeException {

    public SignTokenRejected() {
        super("sign token rejected");
    }
}
