package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.rules.template.TemplateResolution;

import java.time.LocalDate;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * 애그리게이트 밖에서 주입되는 판단 재료(3A 계획 §2): 테넌트 대형 GA 여부, 위탁 패널 판정 함수(Phase 2 포트 어댑터), 확인서에
 * 고정된 서식(항목 출처로 항목값 존재를 판정). 애그리게이트는 저장소를 부르지 않는다.
 */
public record DisclosureContext(boolean largeGa, BiPredicate<InsurerCode, LocalDate> panel, TemplateResolution template) {

    public DisclosureContext {
        Objects.requireNonNull(panel, "panel");
        Objects.requireNonNull(template, "template");
    }
}
