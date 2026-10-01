package com.ga.disclosure.app.cli;

import org.springframework.boot.ExitCodeGenerator;

import java.io.Serial;

/** 업무 거부(봉인 조건 실패 등)로 끝난 CLI 명령 — 종료 코드 2(인자 오류·명령 오류의 1과 구분한다, 3B 계획 §7). */
final class CliRejection extends CliFailure implements ExitCodeGenerator {

    @Serial
    private static final long serialVersionUID = 1L;

    CliRejection(String message) {
        super(message);
    }

    @Override
    public int getExitCode() {
        return 2;
    }
}
