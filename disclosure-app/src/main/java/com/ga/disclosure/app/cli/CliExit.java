package com.ga.disclosure.app.cli;

import org.springframework.boot.ExitCodeGenerator;

import java.io.Serial;

/** 정해진 종료 코드로 끝내기(검증 명령 — 2 불일치, 3 입력 오류, 설계서 §6.7). 출력은 이미 끝났고 이 예외는 종료 코드만 나른다. */
final class CliExit extends CliFailure implements ExitCodeGenerator {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int code;

    CliExit(int code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public int getExitCode() {
        return code;
    }
}
