package com.ga.disclosure.workflow.flag;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code CHAIN_BROKEN} 해소 근거(6B 계획 §7): 작업 행과 해시 체인으로 묶인 {@code VERIFY_RUN} 감사 행만 읽는다 — 보고서를 복호화하지 않는다.
 */
public interface VerifyEvidencePort {

    /** 바인딩된 테넌트의 작업(종류·상태·시작 시각·보고서 해시). 없으면 빈 값. */
    Optional<Job> job(UUID jobId);

    /** 이 보고서 해시를 가진 {@code VERIFY_RUN} 감사 행의 결과({@code MATCH}·{@code MISMATCH}). 없으면 빈 값. */
    Optional<String> verifyResult(String reportSha256);

    record Job(String kind, String status, Optional<Instant> startedAt, Optional<String> reportSha256) {
    }
}
