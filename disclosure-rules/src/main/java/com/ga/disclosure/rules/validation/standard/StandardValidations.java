package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.rules.validation.ValidationRegistry;

import java.util.List;

/**
 * 설계서 §6.2의 검증 규칙 12종을 담은 레지스트리. 어느 규칙을 어떤 순서로 실행할지는 룰 데이터 {@code validations}가 정한다.
 * 규칙 ID 문자열은 각 평가 함수의 등록 지점({@code ID})에 한 번만 나타난다.
 */
public final class StandardValidations {

    private StandardValidations() {
    }

    public static ValidationRegistry registry() {
        return ValidationRegistry.of(List.of(
                new MinCompare(), new DistinctInsurer(), new SameGroup(), new Panel(), new GradeRequired(),
                new GradeUnavailable(), new RankMonotonic(), new Reason(), new TempProduct(), new Requested(),
                new SignerSet(), new FieldRequired()));
    }
}
