package com.ga.disclosure.workflow.feed;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** {@code outbox_event} 읽기·발행 기록 포트(바인딩된 테넌트). 적재는 {@code OutboxPort}가 한다. */
public interface EventFeedStore {

    /** 테넌트의 마지막 seq(없으면 0). */
    long head();

    /** 발행 기록된 마지막 seq(없으면 0). ack는 언제나 앞부분 전체를 표시하므로 발행된 행은 1..이 값이다. */
    long acknowledged();

    /** {@code afterSeq} 다음부터 seq 오름차순 최대 {@code limit}건의 envelope(계약 {@code contracts/events/v1} 모양). */
    List<JsonNode> after(long afterSeq, int limit);

    /** {@code upToSeq} 이하에서 아직 발행 기록이 없는 행에 {@code at}을 한 번 기록하고 행 수를 돌려준다(GD106 — NULL에서 값으로 한 번만). */
    int markPublished(long upToSeq, Instant at);
}
