package com.ga.disclosure.api.error;

/** 라우트 의미상 없는 자원(예: HTTP에 없는 작업 종류 — 앵커, 승인 Q7) — 다른 404와 같은 바이트. */
public final class NotFoundException extends RuntimeException {

    public NotFoundException() {
        super("not found");
    }
}
