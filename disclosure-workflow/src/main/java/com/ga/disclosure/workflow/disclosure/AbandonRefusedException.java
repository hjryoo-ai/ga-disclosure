package com.ga.disclosure.workflow.disclosure;

/** 폐기 함수가 거부했다(SQLSTATE만 — DB 메시지는 싣지 않는다). */
public final class AbandonRefusedException extends RuntimeException {

    private final String sqlState;

    public AbandonRefusedException(String sqlState, Throwable cause) {
        super("draft abandonment refused (" + sqlState + ")", cause);
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }
}
