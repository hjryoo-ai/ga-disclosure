package com.ga.disclosure.api.dto;

/**
 * 준법 플래그 수동 해소(6B): 해소 코드는 룰 {@code complianceQueue.types[type].resolutionCodes}. 근거는 닫힌 모양 — 지금은 {@code CHAIN_BROKEN}의
 * {@code verifyRunJobId} 하나뿐이고 근거를 받지 않는 유형은 비워 둔다.
 */
public record FlagResolveRequest(String resolutionCode, FlagEvidence evidence) {

    public record FlagEvidence(String verifyRunJobId) {
    }
}
