package com.ga.disclosure.app.cli;

import java.io.Serial;

/** CLI 실패(잘못된 인자, 거부된 배포 등). 애플리케이션 시작 실패로 전파되어 종료 코드가 0이 아니다. */
class CliFailure extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    CliFailure(String message) {
        super(message);
    }
}
