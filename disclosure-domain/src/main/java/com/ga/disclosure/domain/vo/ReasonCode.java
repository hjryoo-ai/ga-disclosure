package com.ga.disclosure.domain.vo;

import java.util.regex.Pattern;

/**
 * 추천사유 코드. <b>열거형이 아니다</b> — 허용 코드 목록은 룰 데이터({@code rule_version.body.reasonCodes})에서 온다
 * (CLAUDE.md 절대 규칙 4). 이 타입은 형식만 검사하고 허용 여부는 Phase 1 룰 해석기가 판단한다.
 */
public record ReasonCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    public ReasonCode {
        Patterns.require(FORMAT, value, "reason code");
    }

    public static ReasonCode of(String value) {
        return new ReasonCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
