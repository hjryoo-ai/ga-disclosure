package com.ga.disclosure.audit.outbox;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 아웃박스 적재 포트(V8 {@code outbox_event}·{@code outbox_head}). 바인딩된 테넌트의 <b>현재 업무 트랜잭션</b> 안에서 부른다 — 상태 변경이
 * 롤백되면 이벤트도 없다. 구현은 테넌트 내 갭 없는 {@code seq}를 매기고(머리 + 1, GD106), envelope 전체를 계약 스키마로 검증한 뒤 적재한다
 * (위반은 명령 오류). 이벤트 ID는 구현이 만든다.
 */
public interface OutboxPort {

    /** 이벤트 1건을 적재하고 매긴 {@code seq}를 돌려준다. */
    long append(EventType type, String aggregateId, Instant occurredAt, JsonNode payload);
}
