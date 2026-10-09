package com.ga.disclosure.workflow.gate;

/** 게이트 주체의 분당 한도(룰 {@code gate.perMinutePerPrincipal}) 초과 — 429, 감사하지 않는다(판정 전). */
public final class GateRateLimitedException extends RuntimeException {

    public GateRateLimitedException() {
        super("gate requests exceed the per-minute limit for this principal");
    }
}
