package com.ga.disclosure.rules.resolve;

import java.util.Objects;

/**
 * 룰 본문 {@code masking.<field>}: 화면·문서 표시용 부분 마스킹(설계서 §9, 미결정 §14 #12 — 값은 데이터).
 * 앞 {@code keepFirst}자와 뒤 {@code keepLast}자만 남기고 나머지를 {@code maskChar}로 바꾼다.
 */
public record MaskingRule(int keepFirst, int keepLast, String maskChar) {

    public MaskingRule {
        if (keepFirst < 0 || keepLast < 0) {
            throw new IllegalArgumentException("keepFirst/keepLast must be >= 0");
        }
        Objects.requireNonNull(maskChar, "maskChar");
        if (maskChar.codePointCount(0, maskChar.length()) != 1) {
            throw new IllegalArgumentException("maskChar must be one character");
        }
    }

    /** 코드포인트 단위로 마스킹한다. 남길 글자 수가 전체 이상이면 전부 가린다(원문을 통째로 보이지 않는다). */
    public String apply(String plain) {
        int[] cps = plain.codePoints().toArray();
        StringBuilder out = new StringBuilder();
        boolean revealAll = keepFirst + keepLast >= cps.length;
        for (int i = 0; i < cps.length; i++) {
            boolean keep = !revealAll && (i < keepFirst || i >= cps.length - keepLast);
            if (keep) {
                out.appendCodePoint(cps[i]);
            } else {
                out.append(maskChar);
            }
        }
        return out.toString();
    }
}
