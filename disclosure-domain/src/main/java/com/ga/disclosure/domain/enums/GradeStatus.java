package com.ga.disclosure.domain.enums;

/** 엔진 등급·순위 산출 상태(§4.1). UNAVAILABLE은 "산출불가" 표기 + 관리자 확인 강제(R-GRADE-UNAVAILABLE, Phase 3). */
public enum GradeStatus {
    OK,
    UNAVAILABLE
}
