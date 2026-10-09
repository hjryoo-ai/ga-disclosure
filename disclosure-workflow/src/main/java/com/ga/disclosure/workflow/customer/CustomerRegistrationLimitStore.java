package com.ga.disclosure.workflow.customer;

import java.time.Instant;

/**
 * 고객 등록 한도의 DB 집계(6B 계획 §9.6): 같은 트랜잭션에서 등록 주체별 트랜잭션 advisory 잠금을 잡고 그 주체의 {@code since} 뒤 감사
 * {@code CUSTOMER_REGISTER} 행을 센다 — 인스턴스 메모리가 아니라 다중 인스턴스에서도 같은 한도다.
 */
public interface CustomerRegistrationLimitStore {

    int lockAndCountSince(String subject, Instant since);
}
