package com.ga.disclosure.workflow.retention;

/** 파기 함수가 거부했다(SQLSTATE GD114·22004 등). 메시지는 SQLSTATE뿐 — 함수 메시지는 싣지 않는다. */
public final class DestructionRefusedException extends RuntimeException {

    private final String sqlState;

    public DestructionRefusedException(String sqlState, Throwable cause) {
        super("destruction function refused (" + sqlState + ")", cause);
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }
}
