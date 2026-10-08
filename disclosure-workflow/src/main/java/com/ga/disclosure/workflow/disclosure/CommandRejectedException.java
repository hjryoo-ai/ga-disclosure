package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.workflow.RejectionCategory;

import java.util.Objects;

/**
 * 입력이 명령의 전제를 어긴 명령 오류(카탈로그에 없는 상품·상품군, 없는 고객, 대상 해시가 지금 실패와 다른 예외 승인 등).
 * 업무 트랜잭션은 롤백되고 {@code COMMAND_FAILED}가 남는다. 메시지에 개인정보를 넣지 않는다.
 */
public final class CommandRejectedException extends RuntimeException {

    private final String code;
    private final RejectionCategory category;

    /** 요청 자체가 업무 규칙에 맞지 않는 거부({@link RejectionCategory#INVALID}). */
    public CommandRejectedException(String code, String message) {
        this(code, RejectionCategory.INVALID, message);
    }

    public CommandRejectedException(String code, RejectionCategory category, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.category = Objects.requireNonNull(category, "category");
    }

    public String code() {
        return code;
    }

    public RejectionCategory category() {
        return category;
    }
}
