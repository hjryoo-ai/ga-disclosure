package com.ga.disclosure.workflow.disclosure;

import java.util.Objects;

/**
 * 입력이 명령의 전제를 어긴 명령 오류(카탈로그에 없는 상품·상품군, 없는 고객, 대상 해시가 지금 실패와 다른 예외 승인 등).
 * 업무 트랜잭션은 롤백되고 {@code COMMAND_FAILED}가 남는다. 메시지에 개인정보를 넣지 않는다.
 */
public final class CommandRejectedException extends RuntimeException {

    private final String code;

    public CommandRejectedException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
