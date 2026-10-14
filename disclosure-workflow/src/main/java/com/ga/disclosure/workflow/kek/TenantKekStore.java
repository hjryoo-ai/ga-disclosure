package com.ga.disclosure.workflow.kek;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 바인딩된 테넌트의 KEK 레지스트리({@code tenant_kek}, V21). */
public interface TenantKekStore {

    record Registered(String kekId, boolean current, Instant registeredAt) {
    }

    /** CURRENT KEK ID(테넌트당 하나). 없으면 그 테넌트는 아직 암호화할 수 없다. */
    Optional<String> current();

    /** 등록 순(오래된 것부터). */
    List<Registered> all();

    /** 지금 CURRENT를 RETIRED로(있으면) 바꾸고 {@code kekId}를 CURRENT로 넣는다 — 한 트랜잭션 안에서 부른다. */
    void register(String kekId, Instant at, String by);
}
