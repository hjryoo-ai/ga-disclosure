package com.ga.disclosure.api.dto;

/** 고객 서명 세션 발급: 채널(TOUCH_PAD|REMOTE_LINK|PAPER_SCAN|…, 룰 {@code channels}). */
public record SessionIssueRequest(String channel) {
}
