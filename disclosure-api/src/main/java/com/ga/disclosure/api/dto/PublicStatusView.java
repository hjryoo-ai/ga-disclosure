package com.ga.disclosure.api.dto;

import java.util.List;

/** 고객 화면 상태(열린 세션만): 요구·통과한 본인확인 수단, 열람 여부, 기한. */
public record PublicStatusView(String sessionStatus, List<String> identityRequired, List<String> identityPassed, boolean viewed, String expiresAt) {
}
