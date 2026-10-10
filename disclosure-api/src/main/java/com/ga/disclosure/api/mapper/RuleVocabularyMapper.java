package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.RuleVocabularyView;
import com.ga.disclosure.workflow.vocabulary.RuleVocabularyService;

import java.util.List;

/** 룰 어휘 응답 모양(Phase 8): 코드·표기, 채널·서명자는 enum 이름. */
public final class RuleVocabularyMapper {

    private RuleVocabularyMapper() {
    }

    public static RuleVocabularyView view(RuleVocabularyService.Vocabulary v) {
        return new RuleVocabularyView(v.asOf().toString(), entries(v.reasonCodes()), entries(v.voidReasons()), entries(v.supersedeReasons()),
                entries(v.legalHoldReasons()), entries(v.legalHoldReleaseReasons()), entries(v.draftAbandonReasons()),
                v.flagTypes().stream().map(t -> new RuleVocabularyView.FlagTypeCodes(t.type(), entries(t.resolutionCodes()))).toList(),
                v.channels().stream().map(Enum::name).toList(), v.signerRoles().stream().map(Enum::name).toList());
    }

    public static String etag(RuleVocabularyService.Vocabulary v) {
        return "\"" + v.version() + "\"";
    }

    private static List<RuleVocabularyView.CodeLabel> entries(List<RuleVocabularyService.Entry> entries) {
        return entries.stream().map(e -> new RuleVocabularyView.CodeLabel(e.code(), e.label())).toList();
    }
}
