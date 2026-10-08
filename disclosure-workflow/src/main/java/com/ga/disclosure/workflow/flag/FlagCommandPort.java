package com.ga.disclosure.workflow.flag;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 준법 큐의 쓰기(V14 가드 GD134 — 열린 플래그만, 해소는 한 번, SLA 경과 표시는 한 번). 바인딩된 테넌트 트랜잭션 안에서 부른다. */
public interface FlagCommandPort {

    /** 열린 플래그의 담당자를 쓴다. 이미 닫혔으면 false. */
    boolean assign(UUID flagId, String assignee);

    /** 수동 해소(해소 방식 {@value #MANUAL_RESOLUTION}): 코드·근거(JCS 문자열 또는 null)와 함께 한 번. 이미 닫혔으면 false. */
    boolean resolveManually(UUID flagId, String resolvedBy, Instant at, String resolutionCode, String evidenceJsonOrNull);

    /** 기한이 {@code now} 이전인 열린·미표시 플래그에 경과 시각을 쓴다(오래된 기한 순, 최대 {@code limit}건). 표시한 플래그를 돌려준다. */
    List<Breached> markSlaBreached(Instant now, int limit);

    String MANUAL_RESOLUTION = "COMPLIANCE_RESOLVED";

    record Breached(UUID flagId, String type, Instant dueAt) {
    }
}
