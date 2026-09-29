package com.ga.disclosure.rules.validation;

import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.ResolutionFailure;
import com.ga.disclosure.rules.resolve.RuleResolutionException;
import com.ga.disclosure.rules.template.TemplateResolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 규칙 ID → 평가 함수 레지스트리(설계서 §6.2). 실행기는 유효 룰의 {@code validations}에 나열된 ID만, 그 순서로 실행한다.
 * <ul>
 *   <li>목록의 ID가 하나라도 레지스트리에 없으면 아무것도 실행하지 않고 {@link ResolutionFailure#UNKNOWN_VALIDATION}.</li>
 *   <li>레지스트리에 있어도 목록에 없는 규칙은 실행되지 않는다 — 새 규칙은 평가 함수를 등록하고 데이터로 활성화한다.</li>
 * </ul>
 */
public final class ValidationRegistry {

    private final Map<String, Validation> byId;

    private ValidationRegistry(Map<String, Validation> byId) {
        this.byId = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
    }

    public static ValidationRegistry of(List<Validation> validations) {
        Map<String, Validation> byId = new LinkedHashMap<>();
        for (Validation v : validations) {
            if (byId.put(Objects.requireNonNull(v.id(), "id"), v) != null) {
                throw new IllegalArgumentException("duplicate validation id " + v.id());
            }
        }
        return new ValidationRegistry(byId);
    }

    public Set<String> registeredIds() {
        return byId.keySet();
    }

    /** 실행 계획: 룰의 목록 순서대로 평가 함수. 모르는 ID가 있으면 전부 모아 실패한다. */
    public List<Validation> plan(EffectiveRule rule) {
        List<String> ids = rule.validations();
        List<String> unknown = ids.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new RuleResolutionException(ResolutionFailure.UNKNOWN_VALIDATION,
                    "rule " + rule.globalRuleVersionId() + " lists validations " + unknown + " that are not registered " + byId.keySet());
        }
        return ids.stream().map(byId::get).toList();
    }

    /** 계획을 세운 뒤 전부 실행한다(실패가 있어도 끝까지 — 결과 전체가 감사·화면의 입력이다). */
    public List<ValidationResult> run(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        List<ValidationResult> results = new ArrayList<>();
        for (Validation v : plan(rule)) {
            ValidationResult r = v.evaluate(subject, rule, template);
            if (!r.ruleId().equals(v.id())) {
                throw new IllegalStateException("validation " + v.id() + " reported as " + r.ruleId());
            }
            results.add(r);
        }
        return List.copyOf(results);
    }
}
