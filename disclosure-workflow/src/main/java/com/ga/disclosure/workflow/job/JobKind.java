package com.ga.disclosure.workflow.job;

/**
 * 작업 종류(V12 {@code ck_job_kind}). 잠금 키는 {@link #lockKind()} — dry-run은 파기와 같은 키다(판정 중 파기가 겹치면 dry-run 보고서가 거짓이 된다,
 * 6A 계획 §6.1). 앵커는 CLI 전용이다(승인 Q7 — HTTP 처리기 목록에 없다).
 */
public enum JobKind {
    ANCHOR,
    EXPIRE,
    RECONCILE,
    DESTROY,
    DESTROY_DRY_RUN,
    VERIFY_TENANT,
    NOTIFY,
    IDEMPOTENCY_PURGE;

    public JobKind lockKind() {
        return this == DESTROY_DRY_RUN ? DESTROY : this;
    }
}
