package com.ga.disclosure.rules.validation;

import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;

/**
 * 검증 규칙 하나(평가 함수). 규칙 ID는 등록 지점({@link #id()})에만 나타나고, 실행 여부와 순서는 룰 데이터
 * {@code validations}가 정한다. 임계치·코드값은 전부 {@link EffectiveRule}·{@link TemplateResolution}에서 읽는다.
 */
public interface Validation {

    String id();

    ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template);
}
