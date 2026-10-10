package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.RuleVocabularyView;
import com.ga.disclosure.api.mapper.RuleVocabularyMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.vocabulary.RuleVocabularyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 룰 어휘(Phase 8, 승인 Q8): {@code GET /api/v1/rules/vocabulary} — 설계사·관리자·준법. {@code ETag} = 날짜·병합 룰 본문의 해시. */
@RestController
@RequestMapping("/api/v1/rules/vocabulary")
public class RuleVocabularyController {

    private final RuleVocabularyService vocabulary;

    public RuleVocabularyController(RuleVocabularyService vocabulary) {
        this.vocabulary = vocabulary;
    }

    @GetMapping
    public ResponseEntity<RuleVocabularyView> read(Caller caller) {
        RuleVocabularyService.Vocabulary v = vocabulary.read(caller);
        return ResponseEntity.ok().eTag(RuleVocabularyMapper.etag(v)).body(RuleVocabularyMapper.view(v));
    }
}
