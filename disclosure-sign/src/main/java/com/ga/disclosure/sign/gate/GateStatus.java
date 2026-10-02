package com.ga.disclosure.sign.gate;

/** 청약 게이트 응답의 문서 상태(설계서 §4.4): 완료, 서명 진행 중(봉인·일부 서명), 해당 없음(봉인 전·무효·정정·만료). */
public enum GateStatus {
    COMPLETED,
    PENDING,
    NONE
}
