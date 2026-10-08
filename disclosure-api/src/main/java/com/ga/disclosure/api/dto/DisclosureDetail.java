package com.ga.disclosure.api.dto;

import java.util.List;

/**
 * 확인서 상세(6A 계획 §4.1): 상태·항목·스냅샷 요약·봉인·서명 현황·고정 룰. 고객 개인정보 없음 — {@code customerRef} 가명만. {@code ratioToAvg}는 엔진이 준
 * 불투명 문자열 그대로다(절대 규칙 1). 무효·정정 사유는 코드만(텍스트는 자유 입력 — 싣지 않는다).
 */
public record DisclosureDetail(String disclosureId, String disclosureNo, int version, String supersedesId, String supersededById, String status,
                               String agentId, String customerRef, String groupCode, String consultDate, String ruleVersionId,
                               String tenantRuleVersionId, String templateId, int templateVersion, String issuerMode, List<Item> items,
                               Snapshot snapshot, Seal seal, String voidedAt, String voidReasonCode, String supersedeReasonCode,
                               List<Signature> signatures, String completedAt, String destroyedAt, String asOf) {

    public record Item(int itemNo, String productKey, String insurerCode, String groupCode, String productName, boolean tempProduct,
                       String quoteDocNo, boolean recommended, boolean requestedByCustomer, Grade grade, Recommendation recommendation) {
    }

    /** {@code status} OK면 등급 필드, UNAVAILABLE이면 사유·출처. */
    public record Grade(String status, String gradeCode, String gradeLabel, Integer gradeOrdinal, Integer rankInSet, Boolean tie,
                       String ratioToAvg, String unavailableReason, String source) {
    }

    public record Recommendation(List<String> reasonCodes, String text) {
    }

    public record Snapshot(String snapshotId, String gradingPolicyVersionId, String rankingPolicyVersionId, String tieBreak, String generatedAt) {
    }

    public record Seal(String disclosureNo, String sealedAt, String canonicalHash, String pdfHash, long chainSeq, String retentionUntil) {
    }

    public record Signature(String role, String signedAt) {
    }
}
