package com.ga.disclosure.rules.resolve;

/**
 * 고객 서명 채널 정책(룰 {@code channels.<채널>}, 4 계획 승인 Q5): 허용 여부와 관리자 검토 강제(PAPER_SCAN만 룰에 두며 나머지 채널은 false).
 */
public record ChannelPolicy(boolean enabled, boolean requiresManagerReview) {
}
