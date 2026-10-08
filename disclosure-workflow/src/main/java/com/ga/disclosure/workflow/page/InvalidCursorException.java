package com.ga.disclosure.workflow.page;

/** 열 수 없는 커서(형식·MAC·다른 테넌트·다른 목록) — 400 {@code INVALID_CURSOR}. 메시지에 커서 값을 싣지 않는다. */
public final class InvalidCursorException extends RuntimeException {

    public InvalidCursorException() {
        super("invalid cursor");
    }
}
