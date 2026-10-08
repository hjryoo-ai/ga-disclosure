package com.ga.disclosure.api.dto;

/** 준법 플래그 배정(6B): 담당자 주체(그 플래그의 담당 역할로 연결된 주체). */
public record FlagAssignRequest(String assignee) {
}
