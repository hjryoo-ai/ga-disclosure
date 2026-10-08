/**
 * 비동기 작업(6A 계획 §6, 설계서 §6 {@code job-states}): 작업 리소스({@code async_job}), 테넌트·종류별 세션 잠금(전용 롤 커넥션), 잠금 보유 확인(승인 B1),
 * 암호화 보고서. CLI와 HTTP가 같은 {@link com.ga.disclosure.workflow.job.JobRunner}를 지난다.
 */
package com.ga.disclosure.workflow.job;
