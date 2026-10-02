package com.ga.disclosure.workflow.sign;

import java.util.Optional;

/**
 * 고객 본인확인 입력(4 계획 §2.4): 지금은 생년월일 하나. 값은 이 객체와 대조 메서드의 지역 변수로만 다니고 로그·감사·예외 메시지·DB·{@code toString}에
 * 남지 않는다 — 대조 결과만 남는다(절대 규칙 6, G4). 레코드가 아니다({@code toString}·{@code equals}가 값을 싣지 않게).
 */
public final class IdentityInputs {

    private final String birthDate;

    private IdentityInputs(String birthDateOrNull) {
        this.birthDate = birthDateOrNull;
    }

    public static IdentityInputs birthDate(String raw) {
        return new IdentityInputs(raw);
    }

    public static IdentityInputs none() {
        return new IdentityInputs(null);
    }

    /** 입력된 생년월일 원문(대조 한 곳에서만 쓴다). */
    public Optional<String> birthDateInput() {
        return Optional.ofNullable(birthDate);
    }

    @Override
    public String toString() {
        return "IdentityInputs[birthDate=" + (birthDate == null ? "absent" : "***") + "]";
    }
}
