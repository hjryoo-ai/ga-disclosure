package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.List;

/**
 * 단계 검증 함수: 유스케이스가 확인서에 고정된 룰·서식으로 만든 실행기({@code ValidationRegistry.run})를 애그리게이트에 건넨다.
 * 애그리게이트는 명령을 적용한 <b>후보</b>를 이 함수로 검사하고, 막는 실패가 없을 때만 적용한다(설계서 §6.1 v1.7).
 */
@FunctionalInterface
public interface StageCheck {

    List<ValidationResult> run(ValidationStage stage, ValidationSubject subject);
}
