package com.ga.disclosure.api.dto;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** 피드 한 번(포털 규약 — 승인 Q5): envelope 목록(seq 오름차순), 다음 {@code afterSeq}, 발행자의 마지막 seq, 응답 스키마 버전. */
public record EventFeedView(List<JsonNode> events, long nextSeq, long headSeq, int schemaVersion) {
}
