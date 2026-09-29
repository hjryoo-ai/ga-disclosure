package com.ga.disclosure.rules.resolve;

import java.io.Serial;
import java.util.Objects;

/** 룰·서식 해석 실패. {@link #failure()}로 사유를 구분한다. */
public class RuleResolutionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ResolutionFailure failure;

    public RuleResolutionException(ResolutionFailure failure, String message) {
        super(failure + ": " + message);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public ResolutionFailure failure() {
        return failure;
    }
}
