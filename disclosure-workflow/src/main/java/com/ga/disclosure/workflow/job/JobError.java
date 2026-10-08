package com.ga.disclosure.workflow.job;

/** FAILED 작업의 오류 코드(닫힌 집합, {@code async_job.error_code}). 메시지는 남기지 않는다. */
public enum JobError {
    /** 잠금을 새로 잡은 제출이 남아 있던 활성 행을 닫았다(옛 프로세스가 죽었거나 잠금을 잃었다). */
    INTERRUPTED,
    /** 실행 중 잠금 커넥션을 잃었다 — 보고서를 저장하지 않는다(승인 B1). */
    LOCK_LOST,
    /** 실행기가 작업을 받지 않았다(HTTP 제출). */
    REJECTED,
    /** 유스케이스가 예외로 끝났다. */
    EXECUTION_FAILED,
    /** 보고서 암호화·저장이 실패했다. */
    REPORT_STORE_FAILED
}
