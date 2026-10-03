package com.ga.disclosure.workflow;

/**
 * REPEATABLE READ 트랜잭션이 다른 트랜잭션의 커밋과 부딪혀 롤백됐다(SQLSTATE 40001·23505). 쓰기는 하나도 남지 않았고 처음부터 다시 하면 된다.
 * 메시지는 SQLSTATE뿐이다.
 */
public final class ConcurrentWriteConflict extends RuntimeException {

    private final String sqlState;

    public ConcurrentWriteConflict(String sqlState, Throwable cause) {
        super("concurrent write conflict (" + sqlState + ")", cause);
        this.sqlState = sqlState;
    }

    public String sqlState() {
        return sqlState;
    }
}
