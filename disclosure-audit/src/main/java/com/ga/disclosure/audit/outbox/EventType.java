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
    ComplianceFlagRaised,
    /** Phase 5 파기 ③(추가형, V10). payload는 식별자·번호·시각만. */
    DisclosureDestroyed,
    /** 봉인 전 초안의 폐기(6B, 추가형) — 식별자·시각만. */
    DisclosureAbandoned;

    public int version() {
        return 1;
    }

    /** envelope {@code aggregate.kind}. */
    public String aggregateKind() {
        return this == ComplianceFlagRaised ? "COMPLIANCE_FLAG" : "DISCLOSURE";
    }
}
