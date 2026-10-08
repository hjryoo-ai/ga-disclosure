package com.ga.disclosure.api.dto;

import java.util.List;

/** 관리자 확인: 확인서에 걸린 플래그 전부(열림·닫힘)의 ID — 사유를 확인했다는 표시(4 계획 승인 Q9). */
public record ManagerConfirmationRequest(List<String> acknowledgedFlags) {
}
