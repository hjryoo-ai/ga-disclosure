package com.ga.disclosure.api.dto;

import java.util.List;

/**
 * 룰 어휘(Phase 8): 오늘(KST) 시행 중인 룰의 닫힌 코드 목록만 — 코드와 표기(서명 채널·서명자는 계약 enum 코드만, 표기는 화면 사전). 수·불리언 칸이 없다.
 */
public record RuleVocabularyView(String asOf, List<CodeLabel> reasonCodes, List<CodeLabel> voidReasons, List<CodeLabel> supersedeReasons,
                                 List<CodeLabel> legalHoldReasons, List<CodeLabel> legalHoldReleaseReasons, List<CodeLabel> draftAbandonReasons,
                                 List<FlagTypeCodes> flagTypes, List<String> channels, List<String> signerRoles) {

    public record CodeLabel(String code, String label) {
    }

    public record FlagTypeCodes(String type, List<CodeLabel> resolutionCodes) {
    }
}
