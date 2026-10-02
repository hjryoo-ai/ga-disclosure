/**
 * 아웃박스(설계서 §4.5 v1.9, 4 계획 승인 Q13): 상태 변경과 같은 트랜잭션에서 계약 {@code contracts/events/v1}의 8개 이벤트를 적재한다.
 * 감사 로그처럼 업무 트랜잭션에 붙는 기록이라 워크플로·준법 모듈이 함께 쓰는 이 모듈에 둔다. 적재 순서는 잠금 순서의 마지막이다.
 */
package com.ga.disclosure.audit.outbox;
