package com.ga.disclosure.workflow.disclosure;

import java.util.Objects;

/**
 * 엔진이 스냅샷을 주지 않았다: 연결 실패·타임아웃·오류 응답(400·401·403·404·409·422·5xx). 명령 오류이며({@code COMMAND_FAILED},
 * 업무 트랜잭션 롤백) 준법 플래그는 올리지 않는다 — 응답을 받았으나 틀린 경우({@code GRADE_INCONSISTENT})와 구분한다.
 *
 * @param code 분류 코드(예: {@code ENGINE_CONNECT}, {@code ENGINE_TIMEOUT}, {@code ENGINE_422_NO_POLICY}). 메시지에 요청·응답 본문·토큰을
 *             넣지 않는다.
 */
public final class EngineUnavailableException extends RuntimeException {

    private final String code;

    public EngineUnavailableException(String code, Throwable cause) {
        super("engine unavailable: " + code, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
