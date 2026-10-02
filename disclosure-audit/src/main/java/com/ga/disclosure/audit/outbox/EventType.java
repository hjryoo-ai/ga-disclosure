package com.ga.disclosure.audit.outbox;

/** 계약 이벤트 타입(contracts/events/v1 envelope {@code type}, V8 CHECK). 지금 쓰는 payload 버전은 전부 1이다. */
public enum EventType {
    DisclosureCreated,
    DisclosureSealed,
    SignatureCaptured,
    DisclosureCompleted,
    DisclosureVoided,
    DisclosureSuperseded,
    PolicyLinked,
    ComplianceFlagRaised;

    public int version() {
        return 1;
    }

    /** envelope {@code aggregate.kind}. */
    public String aggregateKind() {
        return this == ComplianceFlagRaised ? "COMPLIANCE_FLAG" : "DISCLOSURE";
    }
}
