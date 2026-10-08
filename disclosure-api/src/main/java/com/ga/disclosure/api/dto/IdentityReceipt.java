package com.ga.disclosure.api.dto;

import java.util.List;

/** 본인확인 결과: 세션, 아직 남은 수단, 실패 수, 세션 폐기 여부. */
public record IdentityReceipt(String sessionId, List<String> missing, int failures, boolean revoked) {
}
