/**
 * Idempotency-Key(6A 계획 §4.2, GD120): 청구·재생·완료와 만료 행 정리. 요청 원문·응답 원문은 저장하지 않는다 — 요청은 해시, 응답은 닫힌 영수증 튜플과 바이트
 * 해시(승인 Q4). 보관·임차 시간은 룰 {@code api.idempotencyTtlHours}·{@code api.idempotencyLeaseSeconds}(실행 시점 오늘 KST의 ACTIVE 룰).
 */
package com.ga.disclosure.workflow.idempotency;
