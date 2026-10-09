package com.ga.disclosure.api.dto;

import java.util.List;

/**
 * 청약 게이트 판정: {@code ALLOWED|BLOCKED}, 사유({@code SATISFIED|NO_DISCLOSURE|CUSTOMER_MISMATCH|AMBIGUOUS|PENDING}), 근거 확인서의 번호·대기 역할·고정
 * 룰 버전(근거가 없으면 null·빈 목록). 개인정보 없음.
 */
public record GateDecisionView(String decision, String reason, String disclosureNo, List<String> pendingRoles, String ruleVersionId) {
}
