package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.workflow.RejectionCategory;

import java.util.Objects;

/**
 * 입력이 명령의 전제를 어긴 명령 오류(카탈로그에 없는 상품·상품군, 없는 고객, 대상 해시가 지금 실패와 다른 예외 승인 등).
 * 업무 트랜잭션은 롤백되고 {@code COMMAND_FAILED}가 남는다. 메시지에 개인정보를 넣지 않는다. 코드는 닫힌 enum({@link Code}, 이벤트 피드는
 * {@code EventFeed.Rejection})이다 — 자유 문자열로 만들 수 없고, 설계서 {@code rejection-categories} 블록과 양방향 대조된다.
 */
public final class CommandRejectedException extends RuntimeException {

    /** 명령 거부 코드(블록의 {@code CommandRejected} 행). */
    public enum Code implements RejectionCategory.Categorized {
        ACTOR_ORG_UNKNOWN,
        AGENT_NOT_LINKED,
        APPROVAL_ROLE_REQUIRED,
        APPROVAL_SUBJECT_MISMATCH,
        FIELD_NOT_EDITABLE,
        UNKNOWN_CUSTOMER,
        UNKNOWN_FIELD,
        UNKNOWN_GROUP,
        UNKNOWN_PRODUCT,
        /** 봉인 뒤의 예외 승인. */
        SEALED(RejectionCategory.CONFLICT),
        /** 6B 중간 회신 ①: 같은 출처·배치 ID에 다른 내용. */
        BATCH_REF_REUSED,
        /** 6B 7단계: 같은 (달, 룰 버전)의 징구율 스냅샷이 이미 있다. */
        SNAPSHOT_EXISTS(RejectionCategory.CONFLICT);

        private final RejectionCategory category;

        Code() {
            this(RejectionCategory.INVALID);
        }

        Code(RejectionCategory category) {
            this.category = category;
        }

        @Override
        public RejectionCategory category() {
            return category;
        }
    }

    private final RejectionCategory.Categorized code;

    public CommandRejectedException(RejectionCategory.Categorized code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code.name();
    }

    public RejectionCategory category() {
        return code.category();
    }
}
