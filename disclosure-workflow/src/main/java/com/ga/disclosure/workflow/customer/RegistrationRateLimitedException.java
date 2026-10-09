package com.ga.disclosure.workflow.customer;

/** 고객 등록 한도(룰 {@code customers.registerPerMinute}) 초과 — 429, 저장·감사 없음, 멱등 키는 묶이지 않는다(6B 계획 §9.6, 10단계 회신 ③). */
public final class RegistrationRateLimitedException extends RuntimeException {

    public RegistrationRateLimitedException() {
        super("customer registrations exceed the per-minute limit for this subject");
    }
}
