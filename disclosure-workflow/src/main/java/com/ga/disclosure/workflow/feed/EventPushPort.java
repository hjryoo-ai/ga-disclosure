package com.ga.disclosure.workflow.feed;

import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 푸시 어댑터 자리(6A 지시문 §5 — <b>인터페이스만</b>, 구현·의존성 없음). 구현이 생기면 피드와 같은 envelope을 seq 오름차순으로 넘기고, 소비자의 수신
 * 확인은 피드 ack와 같은 의미여야 한다(at-least-once — 소비자는 {@code eventId}로 중복을 거른다).
 */
public interface EventPushPort {

    void push(TenantId tenant, List<JsonNode> envelopes);
}
