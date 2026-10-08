package com.ga.disclosure.api.dto;

import java.util.List;

/** 확인서 목록 한 쪽. {@code next}는 다음 쪽 커서(없으면 {@code null}). */
public record DisclosurePage(List<DisclosureSummary> items, String next) {
}
