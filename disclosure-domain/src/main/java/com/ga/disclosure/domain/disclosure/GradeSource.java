package com.ga.disclosure.domain.disclosure;

/**
 * 항목 등급 복사본의 출처(V6 {@code disclosure_item.grade_source}). 엔진 응답에서 온 결과는 {@link #ENGINE}, 이 시스템이 로컬로
 * 표기한 산출불가는 {@link #LOCAL} — 로컬 출처는 임시등록 항목의 {@code UNAVAILABLE(TEMP_PRODUCT)}뿐이다(Phase 2 심사 §3-5).
 */
public enum GradeSource {
    ENGINE,
    LOCAL
}
