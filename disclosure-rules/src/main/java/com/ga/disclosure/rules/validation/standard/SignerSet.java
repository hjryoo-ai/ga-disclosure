package com.ga.disclosure.rules.validation.standard;

import com.ga.disclosure.domain.enums.SignOrder;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.validation.Validation;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 서명자 집합·순서·기한(완료 조건). 필수 서명자는 룰 {@code signerSet}이고 순서 정책은 {@code signOrder}다. 관리자 확인 모드와
 * 서명자 집합의 정합은 룰 스키마가 강제하므로({@code REQUIRED}면 관리자 포함, {@code OPTIONAL}·{@code OFF}면 미포함) 이 규칙은
 * 특정 역할 이름을 모른다. 집합 밖 역할의 서명(예: 선택적 관리자 확인)은 허용한다.
 */
final class SignerSet implements Validation {

    static final String ID = "R-SIGNER-SET";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ValidationResult evaluate(ValidationSubject subject, EffectiveRule rule, TemplateResolution template) {
        List<SignerRole> required = rule.signerSet();
        List<ValidationSubject.SignatureMark> signatures = subject.signatures();
        List<String> problems = new ArrayList<>();

        Set<SignerRole> seen = new HashSet<>();
        List<SignerRole> requiredInOrder = new ArrayList<>();
        Instant previous = null;
        for (ValidationSubject.SignatureMark mark : signatures) {
            if (!seen.add(mark.role())) {
                problems.add(mark.role() + " 서명이 두 번 이상이다");
            }
            if (previous != null && mark.signedAt().isBefore(previous)) {
                problems.add("서명 목록이 시각 순이 아니다");
            }
            previous = mark.signedAt();
            if (required.contains(mark.role())) {
                requiredInOrder.add(mark.role());
            }
        }
        List<SignerRole> missing = required.stream().filter(r -> !seen.contains(r)).toList();
        if (!missing.isEmpty()) {
            problems.add("받지 못한 필수 서명: " + missing);
        }
        if (rule.signOrder() == SignOrder.SEQUENTIAL && missing.isEmpty() && !requiredInOrder.equals(required)) {
            problems.add("서명 순서 " + requiredInOrder + "가 룰 순서 " + required + "와 다르다");
        }
        if (subject.signDeadline().isEmpty()) {
            problems.add("서명 기한이 없다(봉인 전)");
        } else {
            Instant deadline = subject.signDeadline().get();
            signatures.stream().filter(m -> m.signedAt().isAfter(deadline))
                    .forEach(m -> problems.add(m.role() + " 서명이 기한 " + deadline + " 뒤다"));
        }
        return problems.isEmpty()
                ? ValidationResult.pass(ID, "필수 서명 " + required + " 충족(" + rule.signOrder() + ")")
                : ValidationResult.fail(ID, String.join("; ", problems));
    }
}
