package com.ga.disclosure.workflow.gate;

import java.time.Instant;

/**
 * 게이트 한도의 DB 집계(Phase 8 — 6B 이월 ②, 8 계획 승인): 같은 트랜잭션에서 게이트 주체별 트랜잭션 advisory 잠금을 잡고 그 주체의 {@code since} 뒤 감사
 * {@code GATE_DECISION} 행을 센다. 게이트는 판정마다 감사하므로 새 표가 없고, 인스턴스 메모리가 아니라 복제본이 N개여도 같은 한도다(고객 등록 한도와 같은 방식).
 */
public interface GateLimitStore {

    int lockAndCountSince(String subject, Instant since);
}
