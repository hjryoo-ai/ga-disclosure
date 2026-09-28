package com.ga.disclosure.domain.enums;

/** 청약 게이트 강제 수준(D-8). 막는 것은 호출자다. 기본 강도 확정은 §14 #6. */
public enum GateMode {
    BLOCK,
    WARN,
    OFF
}
