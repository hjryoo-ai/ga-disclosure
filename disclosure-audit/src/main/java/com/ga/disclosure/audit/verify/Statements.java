package com.ga.disclosure.audit.verify;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 보고서의 고정 문장(지시문·승인 원문 — 테스트가 대조한다). 날짜·시각은 ISO-8601(UTC {@code Z}).
 */
public final class Statements {

    /** 영수증 없이 패키지만 검증했을 때(지시문 §4, 4 수용심사 결정 4의 "명시적 문장"). */
    public static final String INTERNAL_ONLY = "내부 정합성만 확인. 존재 시각·체인 연속은 영수증 또는 verify tenant가 필요하다.";

    /** 영수증이 있어도 감사 체인 연속은 패키지로 증명되지 않는다(4 수용심사 결정 4 — 감사 행 내보내기는 하지 않는다). */
    public static final String AUDIT_CONTINUITY = "감사 체인 연속은 verify tenant만 확인한다. 패키지의 감사 발췌는 행별 해시만 검사했다.";

    private Statements() {
    }

    /** 상한(지시문 §4): 영수증·경로·토큰이 모두 맞을 때. */
    public static String existedBefore(Instant genTime) {
        return "이 문서는 " + genTime + " 이전에 이 내용으로 존재했다.";
    }

    /** 하한(승인 Q13 대안 — 약한 문장): 직전 앵커의 시각은 자체 기록이다. */
    public static String sealedAfter(LocalDate anchorDate, long sealChainSeq, Instant recordedAt, Instant genTime) {
        return "이 문서는 " + anchorDate + " 앵커의 봉인 체인 머리(seq " + sealChainSeq + ", 기록 시각 " + recordedAt + ") 뒤에 봉인되었다. "
                + "하한의 시각은 자체 기록이며 외부로 증명되는 것은 상한(" + genTime + " 이전)뿐이다.";
    }
}
