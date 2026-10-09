package com.ga.disclosure.workflow.retention;

import com.ga.disclosure.workflow.RejectionCategory;

import java.util.Objects;

/**
 * 보류 요청 거부(업무 거부). 코드는 닫힌 enum({@link Code}) — 설계서 {@code rejection-categories} 블록의 {@code LegalHold} 행과 양방향 대조된다. 메시지에
 * 사유 텍스트를 싣지 않는다.
 */
public final class LegalHoldRejectedException extends RuntimeException {

    public enum Code implements RejectionCategory.Categorized {
        /** 룰 {@code legalHoldReasons} 밖의 사유. */
        UNKNOWN_REASON,
        TEXT_REQUIRED,
        TEXT_TOO_LONG,
        ALREADY_HELD(RejectionCategory.CONFLICT),
        NOT_FOUND,
        ALREADY_RELEASED(RejectionCategory.CONFLICT),
        /** 6B: 파기·폐기·문서 키 파기된 대상. */
        TARGET_ALREADY_DESTROYED(RejectionCategory.CONFLICT),
        BAD_RELEASE_REASON,
        FOUR_EYES_REQUIRED;

        private final RejectionCategory category;

        Code() {
            this(RejectionCategory.INVALID);
        }

        Code(RejectionCategory category) {
            this.category = category;
        }

        /** 이미 보류됨·이미 해제됨·이미 지워진 대상은 상태 충돌, 나머지(사유·텍스트·4-eyes)는 요청 거부. */
        @Override
        public RejectionCategory category() {
            return category;
        }
    }

    private final Code code;

    public LegalHoldRejectedException(Code code) {
        super("legal hold request rejected: " + code.name());
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code.name();
    }

    public RejectionCategory category() {
        return code.category();
    }
}
