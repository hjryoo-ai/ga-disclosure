package com.ga.disclosure.domain.disclosure;

import com.ga.disclosure.domain.grade.RatioLabel;

import java.util.Objects;

/**
 * 항목에 복사된 등급·순위(V6 {@code disclosure_item}의 세 형태 중 산출된 둘). 도메인은 등급 코드의 의미를 모른다 — 서수·순위는
 * 엔진이 준 정수, 비율은 불투명 문자열이다. 정렬하지 않는다(CLAUDE.md 절대 규칙 1).
 */
public sealed interface ItemGrade {

    /** 로컬 산출불가 사유: 임시등록 상품(엔진에 보내지 않는다). 엔진은 이 값을 내지 않는다(계약 1.2.0). */
    String TEMP_PRODUCT_REASON = "TEMP_PRODUCT";

    GradeSource source();

    /** 엔진 OK 결과의 복사본. */
    record Ok(String gradeCode, String gradeLabel, int gradeOrdinal, int rankInSet, boolean tie, RatioLabel ratioToAvg)
            implements ItemGrade {

        public Ok {
            if (gradeCode == null || gradeCode.isBlank() || gradeLabel == null || gradeLabel.isBlank()) {
                throw new IllegalArgumentException("OK grade requires code and label");
            }
            if (gradeOrdinal < 1 || rankInSet < 1) {
                throw new IllegalArgumentException("OK grade requires gradeOrdinal >= 1 and rankInSet >= 1");
            }
            Objects.requireNonNull(ratioToAvg, "ratioToAvg");
        }

        @Override
        public GradeSource source() {
            return GradeSource.ENGINE;
        }
    }

    /** 산출불가: 엔진 사유 원문(ENGINE) 또는 임시등록의 로컬 표기(LOCAL). */
    record Unavailable(String reason, GradeSource source) implements ItemGrade {

        public Unavailable {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("UNAVAILABLE grade requires a reason");
            }
            Objects.requireNonNull(source, "source");
            if (source == GradeSource.LOCAL && !reason.equals(TEMP_PRODUCT_REASON)) {
                throw new IllegalArgumentException("the only local unavailable reason is " + TEMP_PRODUCT_REASON);
            }
        }

        public static Unavailable engine(String reason) {
            return new Unavailable(reason, GradeSource.ENGINE);
        }

        public static Unavailable localTempProduct() {
            return new Unavailable(TEMP_PRODUCT_REASON, GradeSource.LOCAL);
        }
    }
}
