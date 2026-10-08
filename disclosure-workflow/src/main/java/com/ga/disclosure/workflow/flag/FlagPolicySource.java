package com.ga.disclosure.workflow.flag;

import com.ga.platform.core.tenant.TenantId;

import java.time.Instant;

/** 플래그 생성 시점의 정책(바인딩된 테넌트 트랜잭션 안에서 부른다 — 룰 저장소를 같은 트랜잭션으로 읽는다). */
public interface FlagPolicySource {

    FlagPolicy at(TenantId tenant, String type, Instant raisedAt);
}
