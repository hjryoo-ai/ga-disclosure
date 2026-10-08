package com.ga.disclosure.workflow.job;

/** 같은 테넌트·잠금 키의 작업이 이미 돈다(409 {@code JOB_ALREADY_RUNNING}). 행을 만들지 않았다. */
public final class JobAlreadyRunningException extends RuntimeException {

    private final JobKind kind;

    public JobAlreadyRunningException(JobKind kind) {
        super("a " + kind.lockKind() + " job is already running for this tenant");
        this.kind = kind;
    }

    public JobKind kind() {
        return kind;
    }
}
