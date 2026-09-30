package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.ReasonCodeRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 추천 항목마다: 룰 {@code reasonCodes}에 있는 코드만, <b>시스템 자동 부가가 아닌</b> 코드 1개 이상(추천사유는 설계사 입력만 —
 * CLAUDE.md 절대 규칙 7), {@code requiresText} 코드를 고르면 텍스트 필수, 텍스트 길이 ≤ {@code reasonTextMaxLength}.
 */
final class Reason implements Validation {

    static final String ID = "R-REASON";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        Map<ReasonCode, ReasonCodeRule> known = rule.reasonCodes().stream()
                .collect(Collectors.toMap(ReasonCodeRule::code, Function.identity()));
        int maxLength = rule.reasonTextMaxLength();
        List<String> problems = new ArrayList<>();
        for (ValidationSubject.Item item : subject.items()) {
            if (!item.isRecommended()) {
                continue;
            }
            List<ReasonCode> unknown = item.reasonCodes().stream().filter(c -> !known.containsKey(c)).toList();
            if (!unknown.isEmpty()) {
                problems.add(item.label() + ": 룰에 없는 사유 코드 " + unknown);
            }
            boolean agentChosen = item.reasonCodes().stream().anyMatch(c -> known.containsKey(c) && !known.get(c).auto());
            if (!agentChosen) {
                problems.add(item.label() + ": 설계사가 고른 사유 코드가 없다");
            }
            boolean needsText = item.reasonCodes().stream().anyMatch(c -> known.containsKey(c) && known.get(c).requiresText());
            String text = item.reasonText().orElse("");
            if (needsText && text.isBlank()) {
                problems.add(item.label() + ": 텍스트가 필요한 사유 코드인데 텍스트가 없다");
            }
            int length = text.codePointCount(0, text.length());
            if (length > maxLength) {
                problems.add(item.label() + ": 사유 텍스트 " + length + "자가 상한 " + maxLength + "자를 넘는다");
            }
        }
        return problems.isEmpty()
                ? ValidationResult.pass(ID, "추천 항목의 사유가 룰을 충족한다")
                : ValidationResult.fail(ID, String.join("; ", problems));
    }
}
