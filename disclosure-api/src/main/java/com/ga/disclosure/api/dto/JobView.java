package com.ga.disclosure.api.dto;

/** 작업 리소스(계약 {@code Job}). 시각은 ISO-8601 UTC 문자열, 없으면 null. 보고서는 {@code /jobs/{jobId}/report}. */
public record JobView(String jobId, String kind, String status, String channel, String requestedBy, String requestedAt, String startedAt,
                      String finishedAt, String errorCode, String reportSha256) {
}
