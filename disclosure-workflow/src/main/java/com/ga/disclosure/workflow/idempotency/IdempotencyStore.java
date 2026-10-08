package com.ga.disclosure.workflow.idempotency;

import java.time.Instant;
import java.util.Optional;

/**
 * {@code idempotency_key} 포트(바인딩된 테넌트). 쓰기는 전부 조건부다 — 기대한 행 상태가 아니면 {@code false}. 허용 변경(첫 청구·임차 인수·완료 1회·만료 뒤
 * 삭제)은 GD120이 한 번 더 강제한다.
 */
public interface IdempotencyStore {

    /** 첫 청구(진행 중, {@code claim_seq = 1}). 같은 키가 이미 있으면 {@code false}(동시 청구는 먼저 커밋한 쪽이 이긴다). */
    boolean claimNew(String subject, String key, String requestHash, Instant now, Instant expiresAt);

    /** 행을 잠그고 읽는다({@code FOR UPDATE}). */
    Optional<IdempotencyRecord> lock(String subject, String key);

    /** 만료된 행 하나를 지운다(이 프로세스의 시계와 DB 시계 모두로 만료). */
    boolean deleteExpired(String subject, String key, Instant now);

    /** 진행 중 행의 임차 인수: {@code claim_seq = fromSeq + 1}, 청구 시각 전진. */
    boolean takeOver(String subject, String key, int fromSeq, Instant now);

    /** 완료 기록(1회): 그 청구 순번의 진행 중 행일 때만. */
    boolean complete(String subject, String key, int claimSeq, int status, String responseRef, String responseHash);

    /** 만료 행을 최대 {@code limit}건 지운다(오래된 만료부터). */
    int purgeExpired(Instant now, int limit);
}
