package com.ga.disclosure.api.dto;

import java.util.List;

/** 작업 목록 한 쪽(최근 순). {@code next}는 다음 쪽 커서(없으면 null). */
public record JobPage(List<JobView> items, String next) {
}
